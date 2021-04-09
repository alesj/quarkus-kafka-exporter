package io.strimzi.kafkaexporter.server.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * @author Ales Justin
 */
@Retention(RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface InjectedProperties {
    String[] value();

    String[] empties() default {};
}
