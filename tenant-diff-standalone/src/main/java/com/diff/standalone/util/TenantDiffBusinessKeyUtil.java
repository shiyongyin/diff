package com.diff.standalone.util;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Objects;

/**
 * Standalone tenant-diff 模块的业务键（businessKey）工具类。
 *
 * <p>
 * <b>设计动机：</b>采用 {@code "|||"} 作为复合 businessKey 的分隔符，与旧版 release 模块保持一致，
 * 确保跨多种业务类型（INSTRUCTION、API_TEMPLATE、API_DEFINITION 等）的 businessKey 在全局唯一、
 * 可解析，避免不同业务类型的自然键冲突。
 * </p>
 *
 * <p>
 * businessKey 用于跨 tenant 定位“同一业务对象/同一记录”，以避免依赖自增 id（不同 tenant 通常不一致）。
 * </p>
 *
 * <p>
 * 约定：
 * <ul>
 *     <li>主表记录的 businessKey 一般由“业务自然键 + 关键维度”拼接得到。</li>
 *     <li>子表/孙表记录的 businessKey 会携带 main/parent 的 businessKey，用于稳定关联与 Apply 外键替换。</li>
 * </ul>
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
public final class TenantDiffBusinessKeyUtil {
    public static final String DELIMITER = "|||";
    private static final String DELIMITER_REGEX = "\\|\\|\\|";

    private TenantDiffBusinessKeyUtil() {
    }

    /**
     * INSTRUCTION 业务主键解析结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InstructionKey {
        private String instruction;
        private String program;
        private String product;
    }

    /**
     * OCR_TEMPLATE 业务主键解析结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OcrTemplateMainKey {
        private String product;
        private String templateName;
        private String doctypeno;
    }

    /**
     * 解析 INSTRUCTION 业务主键。
     *
     * @param businessKey 格式：instruction|||program|||product
     * @return 解析结果
     * @throws IllegalArgumentException 若格式不正确
     */
    public static InstructionKey parseInstructionMain(String businessKey) {
        String[] parts = splitRequired(businessKey, 3, "INSTRUCTION mainKey");
        return InstructionKey.builder()
            .instruction(emptyToNull(parts[0]))
            .program(emptyToNull(parts[1]))
            .product(emptyToNull(parts[2]))
            .build();
    }

    /**
     * 构建 INSTRUCTION 业务主键。
     *
     * @param instruction 指令名称
     * @param program     程序编码
     * @param product     产品线
     * @return 业务键（格式：instruction|||program|||product）
     */
    public static String buildInstructionMain(String instruction, String program, String product) {
        return String.join(DELIMITER, nullToEmpty(instruction), nullToEmpty(program), nullToEmpty(product));
    }

    /**
     * 构建 xai_instr_param 表的记录业务键。
     *
     * @param code            参数编码
     * @param mainBusinessKey 主表业务键
     * @param baseparamid     基础参数 ID
     * @return 记录键
     */
    public static String buildInstrParam(String code, String mainBusinessKey, Object baseparamid) {
        return String.join(DELIMITER, nullToEmpty(code), nullToEmpty(mainBusinessKey), objToString(baseparamid));
    }

    /**
     * 构建 xai_instr_display 表的记录业务键。
     *
     * @param mainBusinessKey 主表业务键
     * @param property        属性名称
     * @param basedisplayid   基础显示 ID
     * @return 记录键
     */
    public static String buildInstrDisplay(String mainBusinessKey, String property, Object basedisplayid) {
        return String.join(DELIMITER, nullToEmpty(mainBusinessKey), nullToEmpty(property), objToString(basedisplayid));
    }

    /**
     * 构建 xai_recommended 表的记录业务键。
     *
     * @param name              推荐项名称
     * @param mainBusinessKey   主表业务键
     * @param baserecommendedid 基础推荐 ID
     * @return 记录键
     */
    public static String buildRecommended(String name, String mainBusinessKey, Object baserecommendedid) {
        return String.join(DELIMITER, nullToEmpty(name), nullToEmpty(mainBusinessKey), objToString(baserecommendedid));
    }

    /**
     * 构建 xai_recommended_param 表的记录业务键。
     *
     * @param parentBusinessKey 父表业务键（xai_recommended 的 recordKey）
     * @param type              参数类型
     * @param baseparamid       基础参数 ID
     * @param source            源值
     * @param target            目标值
     * @return 记录键
     */
    public static String buildRecommendedParam(String parentBusinessKey, String type, Object baseparamid, Object source, Object target) {
        return String.join(DELIMITER,
            nullToEmpty(parentBusinessKey),
            nullToEmpty(type),
            objToString(baseparamid),
            objToString(source),
            objToString(target)
        );
    }

    /**
     * 构建 xai_enumeration 表的记录业务键。
     *
     * @param name            枚举名称
     * @param mainBusinessKey 主表业务键
     * @param baseenumid      基础枚举 ID
     * @return 记录键
     */
    public static String buildEnumeration(String name, String mainBusinessKey, Object baseenumid) {
        return String.join(DELIMITER, nullToEmpty(name), nullToEmpty(mainBusinessKey), objToString(baseenumid));
    }

    /**
     * 构建 xai_enumeration_value 表的记录业务键。
     *
     * @param parentBusinessKey 父表业务键（xai_enumeration 的 recordKey）
     * @param value             枚举值
     * @param baseenumvalueid   基础枚举值 ID
     * @return 记录键
     */
    public static String buildEnumerationValue(String parentBusinessKey, String value, Object baseenumvalueid) {
        return String.join(DELIMITER, nullToEmpty(parentBusinessKey), nullToEmpty(value), objToString(baseenumvalueid));
    }

    /**
     * 构建 xai_instr_condition 表的记录业务键。
     *
     * @param seq             序号
     * @param mainBusinessKey 主表业务键
     * @param type            条件类型
     * @return 记录键
     */
    public static String buildInstrCondition(Object seq, String mainBusinessKey, String type) {
        return String.join(DELIMITER, objToString(seq), nullToEmpty(mainBusinessKey), nullToEmpty(type));
    }

    /**
     * 构建 xai_operation 表的记录业务键。
     *
     * @param mainBusinessKey 主表业务键
     * @param name            操作名称
     * @param baseoperationid 基础操作 ID
     * @return 记录键
     */
    public static String buildOperation(String mainBusinessKey, String name, Object baseoperationid) {
        return String.join(DELIMITER, nullToEmpty(mainBusinessKey), nullToEmpty(name), objToString(baseoperationid));
    }

    /**
     * 构建 xai_operation_param 表的记录业务键。
     *
     * @param parentBusinessKey 父表业务键（xai_operation 的 recordKey）
     * @param name              参数名称
     * @param baseparamid       基础参数 ID
     * @return 记录键
     */
    public static String buildOperationParam(String parentBusinessKey, String name, Object baseparamid) {
        return String.join(DELIMITER, nullToEmpty(parentBusinessKey), nullToEmpty(name), objToString(baseparamid));
    }

    /**
     * 构建 xai_instruction_prompt 表的记录业务键。
     *
     * @param mainBusinessKey 主表业务键
     * @param seq             序号
     * @return 记录键
     */
    public static String buildInstructionPrompt(String mainBusinessKey, Object seq) {
        return String.join(DELIMITER, nullToEmpty(mainBusinessKey), objToString(seq));
    }

    /**
     * 解析 OCR_TEMPLATE（API_TEMPLATE）业务主键。
     *
     * @param businessKey 格式：product|||templateName|||doctypeno
     * @return 解析结果
     * @throws IllegalArgumentException 若格式不正确
     */
    public static OcrTemplateMainKey parseOcrTemplateMain(String businessKey) {
        String[] parts = splitRequired(businessKey, 3, "API_TEMPLATE mainKey");
        return OcrTemplateMainKey.builder()
            .product(emptyToNull(parts[0]))
            .templateName(emptyToNull(parts[1]))
            .doctypeno(emptyToNull(parts[2]))
            .build();
    }

    /**
     * 构建 API_TEMPLATE/OCR_TEMPLATE 主表的业务键，便于在跨租户 Apply 时复用。
     *
     * @param product      产品线
     * @param templateName 模板名称
     * @param doctypeno    单据类型编号
     * @return 业务键（格式：product|||templateName|||doctypeno）
     */
    public static String buildOcrTemplateMain(String product, String templateName, String doctypeno) {
        return String.join(DELIMITER, nullToEmpty(product), nullToEmpty(templateName), nullToEmpty(doctypeno));
    }

    /**
     * 构建 API_TEMPLATE 明细表的业务键，前缀为主表 businessKey 保持父子链路。
     *
     * @param mainBusinessKey 主表 businessKey（xai_api_template 主键）
     * @param itemcode        明细项编码
     * @param docpart         文档头/身区分
     * @return 键值（格式：mainBusinessKey|||itemcode|||docpart）
     */
    public static String buildOcrTemplateD(String mainBusinessKey, String itemcode, String docpart) {
        return String.join(DELIMITER, nullToEmpty(mainBusinessKey), nullToEmpty(itemcode), nullToEmpty(docpart));
    }

    /**
     * 构建 xai_api_def 表的记录业务键，包含 API 维度与项目信息。
     *
     * @param doctypeno 单据类型编号
     * @param product   产品线
     * @param docpart   单据头/身
     * @param itemcode  API 项编码
     * @return 记录业务键
     */
    public static String buildApiDef(String doctypeno, String product, String docpart, String itemcode) {
        return String.join(DELIMITER, nullToEmpty(doctypeno), nullToEmpty(product), nullToEmpty(docpart), nullToEmpty(itemcode));
    }

    /**
     * 构建 xai_api_biz_d 表的记录业务键，将业务主键与 single doc 维度拼接。
     *
     * @param doctypeno   单据类型
     * @param product     产品线
     * @param businessKey 业务主键（如 xai_api_def）
     * @return 业务键
     */
    public static String buildApiBizD(String doctypeno, String product, String businessKey) {
        return String.join(DELIMITER, nullToEmpty(doctypeno), nullToEmpty(product), nullToEmpty(businessKey));
    }

    // ===================== API_DEFINITION 业务键 =====================

    /**
     * API_DEFINITION 业务主键解析结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiDefinitionKey {
        private String product;
        private String industryType;
        private String apiCode;
    }

    /**
     * 解析 API_DEFINITION 业务主键。
     *
     * @param businessKey 格式：product|||industry_type|||api_code
     * @return 解析结果
     */
    public static ApiDefinitionKey parseApiDefinitionMain(String businessKey) {
        String[] parts = splitRequired(businessKey, 3, "API_DEFINITION mainKey");
        return ApiDefinitionKey.builder()
            .product(emptyToNull(parts[0]))
            .industryType(emptyToNull(parts[1]))
            .apiCode(emptyToNull(parts[2]))
            .build();
    }

    /**
     * 构建 API_DEFINITION 业务主键。
     *
     * @param product      产品线
     * @param industryType 行业类型
     * @param apiCode      API编码
     * @return 业务键
     */
    public static String buildApiDefinitionMain(String product, String industryType, String apiCode) {
        return String.join(DELIMITER, nullToEmpty(product), nullToEmpty(industryType), nullToEmpty(apiCode));
    }

    /**
     * 构建 xai_api_structure_node 表的记录业务键。
     *
     * @param mainBusinessKey 业务主键
     * @param nodePath        节点路径
     * @return 记录键
     */
    public static String buildApiStructureNode(String mainBusinessKey, String nodePath) {
        return String.join(DELIMITER, nullToEmpty(mainBusinessKey), nullToEmpty(nodePath));
    }

    /**
     * 构建 xai_api_def 表的记录业务键。
     *
     * @param parentNodeRecordKey 父节点记录键（xai_api_structure_node 的 recordKey）
     * @param product             产品线
     * @param doctypeno           单据类型编号
     * @param docpart             单头或单身
     * @param itemcode            API项
     * @return 记录键
     */
    public static String buildApiDefRecord(String parentNodeRecordKey, String product,
                                           String doctypeno, String docpart, String itemcode) {
        return String.join(DELIMITER,
            nullToEmpty(parentNodeRecordKey),
            nullToEmpty(product),
            nullToEmpty(doctypeno),
            nullToEmpty(docpart),
            nullToEmpty(itemcode)
        );
    }

    /**
     * 构建 xai_field_library_definition 表的记录业务键。
     *
     * @param fieldCode 字段编码
     * @param product   产品线
     * @param docpart   位置
     * @return 记录键
     */
    public static String buildFieldLibraryDef(String fieldCode, String product, String docpart) {
        return String.join(DELIMITER, nullToEmpty(fieldCode), nullToEmpty(product), nullToEmpty(docpart));
    }

    /**
     * 构建 xai_field_library_definition_item 表的记录业务键。
     *
     * @param parentFieldDefRecordKey 父记录键（xai_field_library_definition 的 recordKey）
     * @param apiDocpart              源字段位置
     * @param itemcode                源字段
     * @return 记录键
     */
    public static String buildFieldLibraryDefItem(String parentFieldDefRecordKey,
                                                  String apiDocpart, String itemcode) {
        return String.join(DELIMITER,
            nullToEmpty(parentFieldDefRecordKey),
            nullToEmpty(apiDocpart),
            nullToEmpty(itemcode)
        );
    }

    private static String[] splitRequired(String value, int expectedParts, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is blank");
        }
        String[] parts = value.split(DELIMITER_REGEX, -1);
        if (parts.length != expectedParts) {
            throw new IllegalArgumentException(label + " format error, expected " + expectedParts + " parts but got " + parts.length);
        }
        return parts;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private static String objToString(Object value) {
        return value == null ? "" : Objects.toString(value, "");
    }
}
