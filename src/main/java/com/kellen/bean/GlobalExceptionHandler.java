package com.kellen.bean;

import com.kellen.utils.exception.ApiExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * MCP 服务统一异常增强。
 * <p>
 * 复用 {@link ApiExceptionHandler} 输出统一响应，避免各 MCP 工具入口自行拼装错误结构。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ApiExceptionHandler {
}
