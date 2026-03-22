package com.diff.standalone.plugin;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.diff.core.domain.model.BusinessData;
import com.diff.core.domain.scope.LoadOptions;
import com.diff.core.domain.scope.ScopeFilter;
import com.diff.core.domain.schema.BusinessSchema;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StandalonePluginRegistryTest {

    @Test
    void constructor_warnsWhenPluginHasMutableField() {
        Logger logger = (Logger) LoggerFactory.getLogger(StandalonePluginRegistry.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new StandalonePluginRegistry(List.of(new MutablePlugin()));
            assertTrue(appender.list.stream().anyMatch(event ->
                event.getFormattedMessage().contains("插件可能包含可变成员字段")
                    && event.getFormattedMessage().contains("mutableState")));
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static final class MutablePlugin implements StandaloneBusinessTypePlugin {
        @SuppressWarnings("FieldMayBeFinal")
        private String mutableState = "danger";

        @Override
        public String businessType() {
            return "MUTABLE";
        }

        @Override
        public BusinessSchema schema() {
            return BusinessSchema.builder().build();
        }

        @Override
        public List<String> listBusinessKeys(Long tenantId, ScopeFilter filter) {
            return List.of();
        }

        @Override
        public BusinessData loadBusiness(Long tenantId, String businessKey, LoadOptions options) {
            return null;
        }
    }
}
