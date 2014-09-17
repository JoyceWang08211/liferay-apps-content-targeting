/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.content.targeting.deploy.hot.extender.internal.activator;

import com.liferay.portal.kernel.bean.BeanLocator;
import com.liferay.portal.kernel.bean.PortletBeanLocatorUtil;
import com.liferay.portal.kernel.deploy.hot.HotDeployEvent;
import com.liferay.portal.kernel.deploy.hot.HotDeployUtil;
import com.liferay.portal.kernel.messaging.DestinationNames;
import com.liferay.portal.kernel.messaging.Message;
import com.liferay.portal.kernel.messaging.MessageBus;
import com.liferay.portal.kernel.messaging.MessageListener;
import com.liferay.portal.kernel.util.PortalLifecycle;
import com.liferay.portal.kernel.util.PortalLifecycleUtil;
import com.liferay.portal.service.BaseLocalService;
import com.liferay.portal.service.BaseService;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import javax.servlet.ServletContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Carlos Sierra
 */
@Component(immediate = true)
public class HotDeployTrackerComponent {

    private ServiceTracker<ServletContext, ServletContext> _serviceTracker;
    private MessageBus _messageBus;

    @Activate
    public void activate(final BundleContext bundleContext) {
        _serviceTracker = new ServiceTracker<ServletContext, ServletContext>(
            bundleContext, ServletContext.class,
            new ServletContextTrackerCustomizer());

        PortalLifecycleUtil.register(new PortalLifecycle() {
            @Override
            public void portalDestroy() {
                _serviceTracker.close();
            }

            @Override
            public void portalInit() {
                _serviceTracker.open();
            }
        });

        _messageBus.registerMessageListener(
            DestinationNames.HOT_DEPLOY,
            new ServiceRegistratorMessageListener());
    }

    @Reference
    public void setPortalServletContext(MessageBus messageBus) {

        _messageBus = messageBus;
    }

    private ClassLoader _getClassLoader(Bundle bundle) {
        BundleWiring bundleWiring = bundle.adapt(BundleWiring.class);

        return bundleWiring.getClassLoader();
    }

    private ServletContext _portalServletContext;

    private class ServletContextTrackerCustomizer
        implements ServiceTrackerCustomizer<ServletContext, ServletContext> {

        @Override
        public ServletContext addingService(
            ServiceReference<ServletContext> serviceReference) {

            Bundle bundle = serviceReference.getBundle();

            if (bundle.getBundleId() == 0) {
                return null;
            }

            BundleContext bundleContext = bundle.getBundleContext();

            ServletContext servletContext = bundleContext.getService(
                serviceReference);

            _osgiDeployContexts.putIfAbsent(
                servletContext.getServletContextName(),
                new OsgiDeployContext(bundleContext));

            HotDeployUtil.fireDeployEvent(
                new HotDeployEvent(servletContext, _getClassLoader(bundle)));

            bundleContext.ungetService(serviceReference);

            return servletContext;
        }

        @Override
        public void modifiedService(
            ServiceReference<ServletContext> serviceReference,
            ServletContext servletContext) {

        }

        @Override
        public void removedService(
            ServiceReference<ServletContext> serviceReference,
            ServletContext servletContext) {

            Bundle bundle = serviceReference.getBundle();

            BundleContext bundleContext = bundle.getBundleContext();

            HotDeployUtil.fireUndeployEvent(
                new HotDeployEvent(servletContext, _getClassLoader(bundle))
            );

            _osgiDeployContexts.remove(servletContext.getServletContextName());

            bundleContext.ungetService(serviceReference);
        }
    }

    public class ServiceRegistratorMessageListener
        implements MessageListener {

        @Override
        public void receive(Message message) {
            String servletContextName =
                (String)message.get("servletContextName");

            OsgiDeployContext osgiDeployContext = _osgiDeployContexts.get(
                servletContextName);

            if (osgiDeployContext == null) {
                return;
            }

            BundleContext bundleContext = osgiDeployContext.getBundleContext();

            BeanLocator beanLocator = PortletBeanLocatorUtil.getBeanLocator(
                servletContextName);

            Map<String,BaseService> servicesMap = beanLocator.locate(
                BaseService.class);

            for (Map.Entry<String, BaseService> serviceEntry :
                servicesMap.entrySet()) {

                BaseService value = serviceEntry.getValue();
                Class<? extends BaseService> valueClass = value.getClass();
                Class serviceInterface = (Class) valueClass.getInterfaces()[0];

                bundleContext.registerService(
                    serviceInterface, serviceEntry.getValue(), null);
            }

            Map<String,BaseLocalService> localServicesMap = beanLocator.locate(
                BaseLocalService.class);

            for (Map.Entry<String, BaseLocalService> localServiceEntry :
                localServicesMap.entrySet()) {

                BaseLocalService value = localServiceEntry.getValue();
                Class<? extends BaseLocalService> valueClass = value.getClass();
                Class serviceInterface = (Class) valueClass.getInterfaces()[0];

                bundleContext.registerService(
                    serviceInterface, localServiceEntry.getValue(), null);
            }

        }

    }

    public static class OsgiDeployContext {
        private BundleContext _bundleContext;


        public BundleContext getBundleContext() {
            return _bundleContext;
        }

        public OsgiDeployContext(BundleContext bundleContext) {

            _bundleContext = bundleContext;
        }

    }

    private ConcurrentHashMap<String, OsgiDeployContext> _osgiDeployContexts =
        new ConcurrentHashMap<String, OsgiDeployContext>();
}