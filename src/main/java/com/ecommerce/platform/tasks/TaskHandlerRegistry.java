package com.ecommerce.platform.tasks;

import com.ecommerce.platform.HandlesTask;
import com.ecommerce.platform.TaskExecution;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

/** Finds {@link HandlesTask} methods; startup fails on a malformed handler or a task type handled twice. */
@Component
class TaskHandlerRegistry implements BeanPostProcessor {

    private static final Duration MIN_INTERVAL = Duration.ofSeconds(1);

    private final Map<String, RegisteredTask> byType = new ConcurrentHashMap<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        if (targetClass.getName().startsWith("com.ecommerce.")) {
            MethodIntrospector.selectMethods(targetClass,
                            (MethodIntrospector.MetadataLookup<HandlesTask>) method ->
                                    method.getAnnotation(HandlesTask.class))
                    .forEach((method, annotation) -> register(bean, method, annotation));
        }
        return bean;
    }

    /** The task types this instance runs; tasks of other types wait for an instance that has them. */
    String[] types() {
        return byType.keySet().toArray(String[]::new);
    }

    RegisteredTask handlerFor(String type) {
        RegisteredTask task = byType.get(type);
        if (task == null) {
            throw new IllegalStateException("No handler runs task type " + type);
        }
        return task;
    }

    boolean handles(String type) {
        return byType.containsKey(type);
    }

    List<RegisteredTask> recurring() {
        return byType.values().stream().filter(RegisteredTask::recurring).toList();
    }

    private void register(Object bean, Method method, HandlesTask annotation) {
        String type = annotation.type();
        if (type.isBlank()) {
            throw new IllegalStateException("@HandlesTask on " + method + " needs a type");
        }
        if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != TaskExecution.class) {
            throw new IllegalStateException("@HandlesTask method " + method + " must take one TaskExecution<T>");
        }
        Class<?> payloadType = ResolvableType.forMethodParameter(method, 0).getGeneric(0).resolve();
        if (payloadType == null) {
            throw new IllegalStateException("@HandlesTask method " + method + " must name its payload type");
        }
        Duration every = annotation.every().isBlank() ? null : DurationStyle.detectAndParse(annotation.every());
        if (every != null && (every.compareTo(MIN_INTERVAL) < 0 || payloadType != Void.class)) {
            throw new IllegalStateException("Recurring task " + type + " must run at most every second and take "
                    + "TaskExecution<Void>");
        }
        Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
        ReflectionUtils.makeAccessible(invocable);
        RegisteredTask existing = byType.putIfAbsent(type, new RegisteredTask(type, bean, invocable, payloadType, every));
        if (existing != null) {
            throw new IllegalStateException("Task type " + type + " is handled by both " + existing.method()
                    + " and " + method);
        }
    }

    record RegisteredTask(String type, Object bean, Method method, Class<?> payloadType, Duration every) {

        boolean recurring() {
            return every != null;
        }

        void invoke(TaskExecution<?> execution) {
            try {
                method.invoke(bean, execution);
            } catch (InvocationTargetException e) {
                switch (e.getCause()) {
                    case RuntimeException runtime -> throw runtime;
                    case Error error -> throw error;
                    default -> throw new IllegalStateException("Task " + type + " failed", e.getCause());
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Task handler for " + type + " is not accessible", e);
            }
        }
    }
}
