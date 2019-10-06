/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.models.impl.injectors;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.models.annotations.Filter;
import org.apache.sling.models.annotations.injectorspecific.InjectionStrategy;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.models.spi.AcceptsNullName;
import org.apache.sling.models.spi.DisposalCallback;
import org.apache.sling.models.spi.DisposalCallbackRegistry;
import org.apache.sling.models.spi.Injector;
import org.apache.sling.models.spi.injectorspecific.AbstractInjectAnnotationProcessor2;
import org.apache.sling.models.spi.injectorspecific.InjectAnnotationProcessor2;
import org.apache.sling.models.spi.injectorspecific.StaticInjectAnnotationProcessorFactory;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.scripting.SlingBindings;
import org.jetbrains.annotations.NotNull;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(property=Constants.SERVICE_RANKING+":Integer=5000", service={Injector.class, StaticInjectAnnotationProcessorFactory.class, AcceptsNullName.class})
public class OSGiServiceInjector implements Injector, StaticInjectAnnotationProcessorFactory, AcceptsNullName {

    private static final Logger log = LoggerFactory.getLogger(OSGiServiceInjector.class);

    private BundleContext bundleContext;

    @Override
    public @NotNull String getName() {
        return "osgi-services";
    }

    @Activate
    public void activate(BundleContext ctx) {
        this.bundleContext = ctx;
    }

    @Override
    public Object getValue(@NotNull Object adaptable, String name, @NotNull Type type, @NotNull AnnotatedElement element,
            @NotNull DisposalCallbackRegistry callbackRegistry) {
        OSGiService annotation = element.getAnnotation(OSGiService.class);
        String filterString = null;
        if (annotation != null) {
            if (StringUtils.isNotBlank(annotation.filter())) {
                filterString = annotation.filter();
            }
        } else {
            Filter filter = element.getAnnotation(Filter.class);
            if (filter != null) {
                filterString = filter.value();
            }
        }
        if (adaptable instanceof SlingHttpServletRequest && StringUtils.isBlank(filterString) && type instanceof Class<?>) {
            //in that case, we'll try to serve the service reference from the request bindings,
            //as it's probably already cached.
            SlingHttpServletRequest request = (SlingHttpServletRequest) adaptable;
            Object service = getValueFromBindings(request, (Class<?>)type);
            if (service != null) {
                return service;
            }
        }
        return getValue(adaptable, type, filterString, callbackRegistry);
    }

    private <T> Object getValueFromBindings(final SlingHttpServletRequest request, Class<T> type) {
        SlingBindings bindings = (SlingBindings) request.getAttribute(SlingBindings.class.getName());
        if (bindings != null && bindings.getSling() != null) {
            return bindings.getSling().getService(type);
        }
        return null;
    }

    private <T> Object getService(Object adaptable, Class<T> type, String filter,
            DisposalCallbackRegistry callbackRegistry) {
        // cannot use SlingScriptHelper since it does not support ordering by service ranking due to https://issues.apache.org/jira/browse/SLING-5665
        try {
            ServiceReference<?>[] refs = bundleContext.getServiceReferences(type.getName(), filter);
            if (refs == null || refs.length == 0) {
                return null;
            } else {
                // sort by service ranking (lowest first) (see ServiceReference.compareTo)
                List<ServiceReference<?>> references = Arrays.asList(refs);
                Collections.sort(references);
                callbackRegistry.addDisposalCallback(new Callback(refs, bundleContext));
                return bundleContext.getService(references.get(references.size() - 1));
            }
        } catch (InvalidSyntaxException e) {
            log.error("invalid filter expression", e);
            return null;
        }
    }

    private <T> Object[] getServices(Object adaptable, Class<T> type, String filter,
            DisposalCallbackRegistry callbackRegistry) {
        // cannot use SlingScriptHelper since it does not support ordering by service ranking due to https://issues.apache.org/jira/browse/SLING-5665
        try {
            ServiceReference<?>[] refs = bundleContext.getServiceReferences(type.getName(), filter);
            if (refs == null || refs.length == 0) {
                return null;
            } else {
                // sort by service ranking (lowest first) (see ServiceReference.compareTo)
                List<ServiceReference<?>> references = Arrays.asList(refs);
                Collections.sort(references);
                // make highest service ranking being returned first
                Collections.reverse(references);
                callbackRegistry.addDisposalCallback(new Callback(refs, bundleContext));
                List<Object> services = new ArrayList<>();
                for (ServiceReference<?> ref : references) {
                    Object service = bundleContext.getService(ref);
                    if (service != null) {
                        services.add(service);
                    }
                }
                return services.toArray();
            }
        } catch (InvalidSyntaxException e) {
            log.error("invalid filter expression", e);
            return null;
        }
    }

    private Object getValue(Object adaptable, Type type, String filterString, DisposalCallbackRegistry callbackRegistry) {
        if (type instanceof Class) {
            Class<?> injectedClass = (Class<?>) type;
            if (injectedClass.isArray()) {
                Object[] services = getServices(adaptable, injectedClass.getComponentType(), filterString,
                        callbackRegistry);
                if (services == null) {
                    return null;
                }
                Object arr = Array.newInstance(injectedClass.getComponentType(), services.length);
                for (int i = 0; i < services.length; i++) {
                    Array.set(arr, i, services[i]);
                }
                return arr;
            } else {
                return getService(adaptable, injectedClass, filterString, callbackRegistry);
            }
        } else if (type instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType) type;
            if (ptype.getActualTypeArguments().length != 1) {
                return null;
            }
            Class<?> collectionType = (Class<?>) ptype.getRawType();
            if (!(collectionType.equals(Collection.class) || collectionType.equals(List.class))) {
                return null;
            }

            Class<?> serviceType = (Class<?>) ptype.getActualTypeArguments()[0];
            Object[] services = getServices(adaptable, serviceType, filterString, callbackRegistry);
            if (services == null) {
                return null;
            }
            return Arrays.asList(services);
        } else {
            log.warn("Cannot handle type {}", type);
            return null;
        }
    }

    private static class Callback implements DisposalCallback {
        private final ServiceReference<?>[] refs;
        private final BundleContext context;

        public Callback(ServiceReference<?>[] refs, BundleContext context) {
            this.refs = refs;
            this.context = context;
        }

        @Override
        public void onDisposed() {
            if (refs != null) {
                for (ServiceReference<?> ref : refs) {
                    context.ungetService(ref);
                }
            }
        }
    }

    @Override
    public InjectAnnotationProcessor2 createAnnotationProcessor(AnnotatedElement element) {
        // check if the element has the expected annotation
        OSGiService annotation = element.getAnnotation(OSGiService.class);
        if (annotation != null) {
            return new OSGiServiceAnnotationProcessor(annotation);
        }
        return null;
    }

    private static class OSGiServiceAnnotationProcessor extends AbstractInjectAnnotationProcessor2 {

        private final OSGiService annotation;

        public OSGiServiceAnnotationProcessor(OSGiService annotation) {
            this.annotation = annotation;
        }

        @Override
        public InjectionStrategy getInjectionStrategy() {
            return annotation.injectionStrategy();
        }

        @Override
        @SuppressWarnings("deprecation")
        public Boolean isOptional() {
            return annotation.optional();
        }
    }


}
