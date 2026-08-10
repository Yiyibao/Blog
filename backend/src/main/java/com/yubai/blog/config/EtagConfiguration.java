package com.yubai.blog.config;

import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

@Configuration
public class EtagConfiguration {
    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> publicApiEtagFilter() {
        var registration = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.setName("publicApiEtagFilter");
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
        registration.addUrlPatterns(
                "/api/v1/posts/*",
                "/api/v1/dishes/*",
                "/api/v1/categories/*",
                "/api/v1/series/*",
                "/api/v1/tags/*",
                "/api/v1/quotes/*",
                "/api/v1/music/*");
        return registration;
    }
}
