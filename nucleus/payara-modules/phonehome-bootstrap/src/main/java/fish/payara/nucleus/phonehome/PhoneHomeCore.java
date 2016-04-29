/*
 * 
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 *  Copyright (c) 2016 C2B2 Consulting Limited and/or its affiliates.
 *  All rights reserved.
 * 
 *  The contents of this file are subject to the terms of the Common Development
 *  and Distribution License("CDDL") (collectively, the "License").  You
 *  may not use this file except in compliance with the License.  You can
 *  obtain a copy of the License at
 *  https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 *  or packager/legal/LICENSE.txt.  See the License for the specific
 *  language governing permissions and limitations under the License.
 * 
 *  When distributing the software, include this License Header Notice in each
 *  file and include the License file at packager/legal/LICENSE.txt.
 * 
 */
package fish.payara.nucleus.phonehome;

import com.sun.enterprise.config.serverbeans.Domain;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import org.glassfish.api.StartupRunLevel;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.api.event.EventListener;
import org.glassfish.api.event.EventTypes;
import org.glassfish.api.event.Events;
import org.glassfish.hk2.runlevel.RunLevel;
import org.jvnet.hk2.annotations.Service;

/**
 *
 * @author David Weaver
 */
@Service(name = "phonehome-core")
@RunLevel(StartupRunLevel.VAL)
public class PhoneHomeCore implements EventListener {
    
    private static final String THREAD_NAME = "PhoneHomeThread";
    
    private static PhoneHomeCore theCore;
    private boolean enabled;
    
    private ScheduledExecutorService executor;
    
    @Inject
    @Named(ServerEnvironment.DEFAULT_INSTANCE_NAME)
    PhoneHomeRuntimeConfiguration configuration;
    
    @Inject
    private ServerEnvironment env;
    
    @Inject
    private Events events;
    
    @Inject
    private Domain domain;
    
    @PostConstruct
    public void postConstruct() {
        theCore = this;
        events.register(this);
        
        if (env.isDas()) {
             
            if (configuration == null) {
                enabled = true;
            } else {
                enabled = Boolean.valueOf(configuration.getEnabled());
            }
            
        } else {
            enabled = false;
        }        
    }
    
    /**
     *
     * @param event
     */
    @Override
    public void event(Event event) {
        if (event.is(EventTypes.SERVER_STARTUP)) {
            bootstrapPhoneHome();
        } else if (event.is(EventTypes.SERVER_SHUTDOWN)) {
            shutdownPhoneHome();
        }
    }
    
    private void bootstrapPhoneHome() {
        
        if (enabled) {
            executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, THREAD_NAME);
                }
            });
            executor.scheduleAtFixedRate(new PhoneHomeTask(domain, env), 0, 1, TimeUnit.DAYS);
        }
    }
    
    private void shutdownPhoneHome() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
    
    public void enable(){
        setEnabled(true);
    }
    public void disable(){
        setEnabled(false);
    }
    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
    
    public void start() {
        if (this.enabled) {
            shutdownPhoneHome();
            bootstrapPhoneHome();         
        } else {
            this.enabled = true;
            bootstrapPhoneHome();
        }
    }
    public void stop() {
        if (this.enabled) {
            this.enabled = false;
            shutdownPhoneHome();
        }
    }
}
