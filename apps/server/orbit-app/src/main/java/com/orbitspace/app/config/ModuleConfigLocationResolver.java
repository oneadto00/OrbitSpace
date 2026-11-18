package com.orbitspace.app.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationNotFoundException;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.config.StandardConfigDataLocationResolver;
import org.springframework.boot.context.config.StandardConfigDataResource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Expand the module import across jars, then use Boot's standard YAML config-data loader for each URL. */
public final class ModuleConfigLocationResolver implements ConfigDataLocationResolver<StandardConfigDataResource> {
    private static final String LOCATION = "classpath*:orbit-mod-*.yml";
    private final PathMatchingResourcePatternResolver resources;
    private final StandardConfigDataLocationResolver delegate;

    public ModuleConfigLocationResolver(DeferredLogFactory logs, Binder binder, ResourceLoader loader) {
        resources = new PathMatchingResourcePatternResolver(loader);
        delegate = new StandardConfigDataLocationResolver(logs, binder, loader);
    }

    @Override
    public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        return LOCATION.equals(location.getValue());
    }

    @Override
    public List<StandardConfigDataResource> resolve(ConfigDataLocationResolverContext context,
            ConfigDataLocation location) {
        try {
            var matches = resources.getResources(LOCATION);
            if (matches.length == 0 && !location.isOptional()) {
                throw new ConfigDataLocationNotFoundException(location);
            }
            Arrays.sort(matches, Comparator.comparing(Resource::getFilename));
            var resolved = new ArrayList<StandardConfigDataResource>();
            for (var resource : matches) {
                var url = ConfigDataLocation.of(resource.getURL().toExternalForm());
                resolved.addAll(delegate.resolve(context, url));
            }
            return resolved;
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not enumerate module configuration", ex);
        }
    }
}
