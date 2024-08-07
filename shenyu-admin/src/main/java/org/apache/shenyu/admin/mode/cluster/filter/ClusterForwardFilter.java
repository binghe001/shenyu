/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shenyu.admin.mode.cluster.filter;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.shenyu.admin.config.properties.ClusterProperties;
import org.apache.shenyu.admin.mode.cluster.service.ClusterSelectMasterService;
import org.apache.shenyu.admin.model.dto.ClusterMasterDTO;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.Resource;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Objects;

/**
 * Cluster forward filter.
 */
public class ClusterForwardFilter extends OncePerRequestFilter {
    
    private static final Logger LOG = LoggerFactory.getLogger(ClusterForwardFilter.class);
    
    private static final PathMatcher PATH_MATCHER = new AntPathMatcher();
    
    @Resource
    private RestTemplate restTemplate;
    
    @Resource
    private ClusterSelectMasterService clusterSelectMasterService;
    
    @Resource
    private ClusterProperties clusterProperties;
    
    @Override
    protected void doFilterInternal(@NotNull final HttpServletRequest request,
                                    @NotNull final HttpServletResponse response,
                                    @NotNull final FilterChain filterChain) throws ServletException, IOException {
        String method = request.getMethod();
        if (StringUtils.equals(HttpMethod.OPTIONS.name(), method)) {
            filterChain.doFilter(request, response);
            return;
        }
        
        if (clusterSelectMasterService.isMaster()) {
            filterChain.doFilter(request, response);
            return;
        }
        // this node is not master
        String uri = request.getRequestURI();
        String requestContextPath = request.getContextPath();
        String replaced = uri.replaceAll(requestContextPath, "");
        boolean anyMatch = clusterProperties.getForwardList()
                .stream().anyMatch(x -> PATH_MATCHER.match(x, replaced));
        if (!anyMatch) {
            filterChain.doFilter(request, response);
            return;
        }
        // cluster forward request to master
        forwardRequest(request, response);
    }
    
    private void forwardRequest(final HttpServletRequest request,
                                final HttpServletResponse response) throws IOException {
        String targetUrl = getForwardingUrl(request);
        
        LOG.info("forwarding current uri: {} method: {} request to target url: {}", request.getRequestURI(), request.getMethod(), targetUrl);
        // Create request entity
        HttpHeaders headers = new HttpHeaders();
        // Copy request headers
        copyHeaders(request, headers);
        HttpEntity<byte[]> requestEntity = new HttpEntity<>(getBody(request), headers);
        // Send request
        ResponseEntity<byte[]> responseEntity = restTemplate.exchange(targetUrl, HttpMethod.valueOf(request.getMethod()), requestEntity, byte[].class);

        // Set response status and headers
        response.setStatus(responseEntity.getStatusCodeValue());
        // Copy response headers
        copyHeaders(responseEntity.getHeaders(), response);
        // fix cors error
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE");
        // write response back
        IOUtils.copy(new ByteArrayInputStream(Objects.requireNonNull(responseEntity.getBody())), response.getOutputStream());
        response.getOutputStream().flush();
    }
    
    @NotNull
    private String getForwardingUrl(final HttpServletRequest request) {
        ClusterMasterDTO master = clusterSelectMasterService.getMaster();
        String host = master.getMasterHost();
        String port = master.getMasterPort();
        String masterContextPath = master.getContextPath();
        
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpRequest(new ServletServerHttpRequest(request))
                .host(host)
                .port(port);
        String originalPath = builder.build().getPath();
        
        if (StringUtils.isNotEmpty(originalPath)) {
            // remove current context path
            String currentContextPath = request.getContextPath();
            if (StringUtils.isNotEmpty(currentContextPath) && originalPath.startsWith(currentContextPath)) {
                originalPath = originalPath.substring(originalPath.indexOf(currentContextPath) + currentContextPath.length());
            }
        }
        if (StringUtils.isNoneBlank(masterContextPath)) {
            originalPath = masterContextPath + originalPath;
        }
        builder.replacePath(originalPath);
        
        return builder.toUriString();
    }
    
    private void copyHeaders(final HttpServletRequest request, final HttpHeaders headers) {
        Collections.list(request.getHeaderNames())
                .forEach(headerName -> headers.add(headerName, removeSpecial(request.getHeader(headerName))));
    }
    
    private void copyHeaders(final HttpHeaders sourceHeaders, final HttpServletResponse response) {
        sourceHeaders.forEach((headerName, headerValues) -> {
            String name = removeSpecial(headerName);
            if (!response.containsHeader(name)) {
                headerValues.forEach(headerValue -> {
                    response.addHeader(name, removeSpecial(headerValue));
                });
            }
        });
    }
    
    private String removeSpecial(final String str) {
        return str.replaceAll("\r", "").replaceAll("\n", "");
    }
    
    private byte[] getBody(final HttpServletRequest request) throws IOException {
        InputStream is = request.getInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            baos.write(buffer, 0, bytesRead);
        }
        return baos.toByteArray();
    }
    
}
