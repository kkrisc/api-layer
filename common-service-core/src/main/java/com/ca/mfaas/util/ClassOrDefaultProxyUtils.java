/*
 * This program and the accompanying materials are made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Copyright Contributors to the Zowe Project.
 */
package com.ca.mfaas.util;

import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Purpose of this library of methods is to support multiple implementation and serve them as under one interface.
 *
 * It could be helpful if implementation is available only on specific environment (ie. libraries in IBM JVM, which
 * cannot be distributed with project.). It is necessary to generate new interface, which cover both implementation
 * (dummy for local purpose and original, represented as fully class name). This util will create implementation by
 * class name or create a local (dummy) implementation. Outside this library it will be used and programmer don't
 * need test if class exists or not.
 *
 * Created proxy offer also interface @link #MethodInvocationHandler to check state of created proxy. This is the
 * reason exclude method names getImplementationClass and isUsingBaseImplementation from proxied object. Any object
 * cannot have methods with the names without attributes. In case of conflict methods for checking state of proxy
 * has higher priority.
 *
 * How to use that:
 * 1. create interface A for proxied object (a subset of methods is enough)
 * 2. create dummy implementation B (must implement the interface)
 * 3. create proxy object
 *
 * A i=ClassOrDefaultProxyUtils.createProxy(A.class, "<full name of class>", () -> new B());
 *
 * 4. you can test if implementation is dummy or not
 *
 * if (!((ClassOrDefaultProxyUtils.ClassOrDefaultProxyState) i).isUsingBaseImplementation()) {
 *     log.error("The searched class was not found, use " +
 *      ((ClassOrDefaultProxyUtils.ClassOrDefaultProxyState) i).isUsingBaseImplementation() +
 *      "instanceof");
 * }
 *
 */
@Slf4j
public final class ClassOrDefaultProxyUtils {

    /**
     * Create a proxy, which implement interfaceClass and ClassOrDefaultProxyState. This proxy will call object created
     * for defaultImplementation class. If this object is not available it will call defaultImplementation instance of.
     * Both implementationClassName and defaultImplementation have to have default constructor to be created.
     *
     * @param interfaceClass Interface of created proxy
     * @param implementationClassName Full name of prefer implementation
     * @param defaultImplementation Supplier to fetch implementation to use, if the prefer one is missing
     * @param <T> Common interface for prefer and default implementation
     * @return Proxy object implementing interfaceClass and ClassOrDefaultProxyState
     */
    public static <T> T createProxy(Class<T> interfaceClass, String implementationClassName, Supplier<? extends T> defaultImplementation) {
        ObjectUtil.requireNotNull(interfaceClass, "interfaceClass can't be null");
        ObjectUtil.requireNotEmpty(implementationClassName, "implementationClassName can't be empty");
        ObjectUtil.requireNotNull(defaultImplementation, "defaultImplementation can't be null");

        try {
            final Class<?> implementationClazz = Class.forName(implementationClassName);
            final Object implementation = implementationClazz.getDeclaredConstructor().newInstance();
            return makeProxy(interfaceClass, implementation, true);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            log.warn("Implementation {} is not available, it will continue with default one {} : " + e.getLocalizedMessage(), implementationClassName, defaultImplementation);
        }

        return makeProxy(interfaceClass, defaultImplementation.get(), false);
    }

    private static <T> T makeProxy(Class<T> interfaceClass, Object implementation, boolean usingBaseImplementation) {
        return (T) Proxy.newProxyInstance(
            ClassOrDefaultProxyUtils.class.getClassLoader(),
            new Class<?>[] {interfaceClass, ClassOrDefaultProxyUtils.ClassOrDefaultProxyState.class},
            new MethodInvocationHandler(implementation, usingBaseImplementation));
    }

    /**
     * Interface to check state of created proxy object
     */
    public interface ClassOrDefaultProxyState {

        /**
         *
         * @return class which is now proxied. It could be one of implementationClassName or defaultImplementation
         */
        public Class<?> getImplementationClass();

        /**
         *
         * @return true if proxy use the original class, false if is using default (dummy) class
         */
        public boolean isUsingBaseImplementation();

    }

    private static class MethodInvocationHandler implements InvocationHandler, ClassOrDefaultProxyState {

        private final Map<String, EndPoint> mapping = new HashMap<>();

        private final boolean usingBaseImplementation;
        private final Object implementation;

        public MethodInvocationHandler(Object implementation, boolean usingBaseImplementation) {
            this.usingBaseImplementation = usingBaseImplementation;
            this.implementation = implementation;

            this.initMapping();
        }

        private void addMapping(Object target, Method caller, Method callee) {
            final String key = ObjectUtil.getMethodIdentifier(caller);
            final EndPoint endPoint = new EndPoint(target, callee);
            mapping.put(key, endPoint);
        }

        private void initMapping() {
            // first map methods of target
            Class<?> clazz = implementation.getClass();
            while (true) {
                for (final Method method : clazz.getDeclaredMethods()) {
                    addMapping(implementation, method, method);
                }

                // the highest superclass (Object) was scanned, end the loop
                if (clazz == Object.class) break;

                // check also superclass
                clazz = clazz.getSuperclass();
            }

            // second check the state interface. It has higher priority, could rewrite previous mapping
            for (final Method method : ClassOrDefaultProxyState.class.getDeclaredMethods()) {
                addMapping(this, method, method);
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            final String methodKey = ObjectUtil.getMethodIdentifier(method);
            final EndPoint endPoint = mapping.get(methodKey);

            if (endPoint == null) {
                throw new NoSuchMethodException(String.format("Cannot found method %s", endPoint));
            }

            return endPoint.invoke(args);
        }

        @Override
        public Class<?> getImplementationClass() {
            return implementation.getClass();
        }

        @Override
        public boolean isUsingBaseImplementation() {
            return usingBaseImplementation;
        }

        @Value
        @AllArgsConstructor
        private static final class EndPoint {

            private final Object target;
            private final Method method;

            public Object invoke(Object[] args) throws InvocationTargetException, IllegalAccessException {
                return method.invoke(target, args);
            }

        }

    }

}