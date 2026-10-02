package com.ecommerce.platform.messaging;

import com.ecommerce.platform.HandlesMessage;
import com.ecommerce.platform.IncomingMessage;
import com.ecommerce.platform.MessageType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

/**
 * Finds {@link HandlesMessage} methods on the application's beans and routes message types to them. Startup fails on
 * a malformed handler or a consumer name used twice.
 */
@Component
class MessageHandlerRegistry implements BeanPostProcessor {

    private final Map<String, RegisteredHandler> byConsumer = new ConcurrentHashMap<>();
    private final Map<String, List<RegisteredHandler>> byType = new ConcurrentHashMap<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        if (targetClass.getName().startsWith("com.ecommerce.")) {
            MethodIntrospector.selectMethods(targetClass,
                            (MethodIntrospector.MetadataLookup<HandlesMessage>) method ->
                                    method.getAnnotation(HandlesMessage.class))
                    .forEach((method, annotation) -> register(bean, method, annotation.consumer()));
        }
        return bean;
    }

    /** Where a message of this type goes: each subscribed handler, plus its topic if it has one. */
    List<String> destinationsFor(MessageType type) {
        List<String> destinations = new ArrayList<>();
        byType.getOrDefault(MessageTypes.key(type), List.of())
                .forEach(handler -> destinations.add(OutboxRepository.HANDLER_PREFIX + handler.consumer()));
        if (!type.topic().isBlank()) {
            destinations.add(OutboxRepository.KAFKA_PREFIX + type.topic());
        }
        return destinations;
    }

    /** The destinations this instance can deliver; rows for other consumers wait for an instance that has them. */
    String[] handlerDestinations() {
        return byConsumer.keySet().stream().map(consumer -> OutboxRepository.HANDLER_PREFIX + consumer)
                .toArray(String[]::new);
    }

    RegisteredHandler handlerFor(String destination) {
        RegisteredHandler handler = byConsumer.get(destination.substring(OutboxRepository.HANDLER_PREFIX.length()));
        if (handler == null) {
            throw new IllegalStateException("No handler for destination " + destination);
        }
        return handler;
    }

    private void register(Object bean, Method method, String consumer) {
        if (consumer.isBlank()) {
            throw new IllegalStateException("@HandlesMessage on " + method + " needs a consumer name");
        }
        if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != IncomingMessage.class) {
            throw new IllegalStateException("@HandlesMessage method " + method + " must take one IncomingMessage<T>");
        }
        Class<?> payloadType = ResolvableType.forMethodParameter(method, 0).getGeneric(0).resolve();
        if (payloadType == null) {
            throw new IllegalStateException("@HandlesMessage method " + method + " must name its payload type");
        }
        MessageType type = MessageTypes.of(payloadType);
        Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
        ReflectionUtils.makeAccessible(invocable);
        RegisteredHandler handler = new RegisteredHandler(consumer, bean, invocable, payloadType);
        RegisteredHandler existing = byConsumer.putIfAbsent(consumer, handler);
        if (existing != null) {
            throw new IllegalStateException("Consumer " + consumer + " is declared by both " + existing.method()
                    + " and " + method);
        }
        byType.computeIfAbsent(MessageTypes.key(type), key -> new CopyOnWriteArrayList<>()).add(handler);
    }

    record RegisteredHandler(String consumer, Object bean, Method method, Class<?> payloadType) {

        void invoke(IncomingMessage<?> message) {
            try {
                method.invoke(bean, message);
            } catch (InvocationTargetException e) {
                switch (e.getCause()) {
                    case RuntimeException runtime -> throw runtime;
                    case Error error -> throw error;
                    default -> throw new IllegalStateException("Handler " + consumer + " failed", e.getCause());
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Handler " + consumer + " is not accessible", e);
            }
        }
    }
}
