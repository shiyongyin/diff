package com.diff.demo;

import com.diff.core.domain.apply.*;
import com.diff.core.domain.diff.DiffType;
import com.diff.core.domain.exception.ErrorCode;
import com.diff.core.domain.exception.TenantDiffException;
import com.diff.standalone.service.TenantDiffStandaloneApplyService;
import com.diff.standalone.service.TenantDiffStandaloneService;
import com.diff.standalone.web.dto.request.CreateDiffSessionRequest;
import com.diff.standalone.web.dto.response.TenantDiffApplyExecuteResponse;
import com.diff.core.domain.scope.TenantModelScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("并发 Apply 门禁测试")
class ConcurrentApplyTest {

    @Autowired
    private TenantDiffStandaloneService diffService;

    @Autowired
    private TenantDiffStandaloneApplyService applyService;

    private Long createAndCompare() {
        CreateDiffSessionRequest request = CreateDiffSessionRequest.builder()
            .sourceTenantId(1L)
            .targetTenantId(2L)
            .scope(TenantModelScope.builder()
                .businessTypes(List.of("EXAMPLE_PRODUCT"))
                .build())
            .build();
        Long sessionId = diffService.createSession(request);
        diffService.runCompare(sessionId);
        return sessionId;
    }

    private ApplyPlan buildExecutePlan(Long sessionId) {
        return applyService.buildPlan(
            sessionId, ApplyDirection.A_TO_B,
            ApplyOptions.builder()
                .mode(ApplyMode.EXECUTE)
                .businessTypes(List.of("EXAMPLE_PRODUCT"))
                .diffTypes(List.of(DiffType.INSERT, DiffType.UPDATE))
                .allowDelete(false)
                .build()
        );
    }

    @Test
    @DisplayName("2线程同时 Apply：仅1个成功，另1个抛出 APPLY_CONCURRENT_CONFLICT")
    void concurrentApply_onlyOneSucceeds() throws Exception {
        Long sessionId = createAndCompare();

        ApplyPlan plan = applyService.buildPlan(
            sessionId, ApplyDirection.A_TO_B,
            ApplyOptions.builder()
                .mode(ApplyMode.EXECUTE)
                .businessTypes(List.of("EXAMPLE_PRODUCT"))
                .diffTypes(List.of(DiffType.INSERT, DiffType.UPDATE))
                .allowDelete(false)
                .build()
        );

        int threadCount = 2;
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    TenantDiffApplyExecuteResponse response = applyService.execute(plan);
                    if (response.getStatus() == ApplyRecordStatus.SUCCESS) {
                        successCount.incrementAndGet();
                    }
                } catch (TenantDiffException e) {
                    if (e.getErrorCode() == ErrorCode.APPLY_CONCURRENT_CONFLICT) {
                        conflictCount.incrementAndGet();
                    }
                }
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdown();

        assertEquals(1, successCount.get(), "应有且仅有 1 个线程成功执行 Apply");
        assertEquals(1, conflictCount.get(), "应有 1 个线程收到 APPLY_CONCURRENT_CONFLICT");
    }

    @Test
    @DisplayName("不同 session 同目标租户并发 Apply：仅1个成功，另1个抛出 APPLY_TARGET_BUSY")
    void concurrentApply_onDifferentSessionsSameTarget_onlyOneSucceeds() throws Exception {
        Long sessionId1 = createAndCompare();
        Long sessionId2 = createAndCompare();

        ApplyPlan plan1 = buildExecutePlan(sessionId1);
        ApplyPlan plan2 = buildExecutePlan(sessionId2);

        int threadCount = 2;
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger leaseConflictCount = new AtomicInteger(0);
        List<Future<?>> futures = new java.util.ArrayList<>();

        futures.add(executor.submit(() -> runApplyWithBarrier(plan1, readyLatch, startLatch, successCount, leaseConflictCount)));
        futures.add(executor.submit(() -> runApplyWithBarrier(plan2, readyLatch, startLatch, successCount, leaseConflictCount)));

        readyLatch.await();
        startLatch.countDown();

        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdown();

        assertEquals(1, successCount.get(), "应有且仅有 1 个 session 成功执行 Apply");
        assertEquals(1, leaseConflictCount.get(), "应有 1 个 session 收到 APPLY_TARGET_BUSY");
    }

    private void runApplyWithBarrier(ApplyPlan plan,
                                     CountDownLatch readyLatch,
                                     CountDownLatch startLatch,
                                     AtomicInteger successCount,
                                     AtomicInteger leaseConflictCount) {
        readyLatch.countDown();
        try {
            startLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            TenantDiffApplyExecuteResponse response = applyService.execute(plan);
            if (response.getStatus() == ApplyRecordStatus.SUCCESS) {
                successCount.incrementAndGet();
            }
        } catch (TenantDiffException e) {
            if (e.getErrorCode() == ErrorCode.APPLY_TARGET_BUSY) {
                leaseConflictCount.incrementAndGet();
            }
        }
    }
}
