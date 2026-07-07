package com.openat.support.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 내부 전용 컨트롤러 표시. {@code /api/v1} 프리픽스에서 제외되어 {@code /internal} 원형 경로를 사용한다. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface InternalApi {}
