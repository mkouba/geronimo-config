/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.geronimo.config;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.geronimo.config.converters.BooleanConverter;
import org.apache.geronimo.config.converters.DoubleConverter;
import org.apache.geronimo.config.converters.DurationConverter;
import org.apache.geronimo.config.converters.FloatConverter;
import org.apache.geronimo.config.converters.InstantConverter;
import org.apache.geronimo.config.converters.IntegerConverter;
import org.apache.geronimo.config.converters.LocalDateConverter;
import org.apache.geronimo.config.converters.LocalDateTimeConverter;
import org.apache.geronimo.config.converters.LocalTimeConverter;
import org.apache.geronimo.config.converters.LongConverter;
import org.apache.geronimo.config.converters.OffsetDateTimeConverter;
import org.apache.geronimo.config.converters.OffsetTimeConverter;
import org.apache.geronimo.config.converters.StringConverter;
import org.apache.geronimo.config.converters.URLConverter;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;

import javax.annotation.Priority;
import javax.enterprise.inject.Typed;
import javax.enterprise.inject.Vetoed;

/**
 * @author <a href="mailto:struberg@apache.org">Mark Struberg</a>
 * @author <a href="mailto:johndament@apache.org">John D. Ament</a>
 */
@Typed
@Vetoed
public class ConfigImpl implements Config {
    protected Logger logger = Logger.getLogger(ConfigImpl.class.getName());

    protected List<ConfigSource> configSources = new ArrayList<>();
    protected Map<Type, Converter> converters = new HashMap<>();


    public ConfigImpl() {
        registerDefaultConverter();
    }

    private void registerDefaultConverter() {
        converters.put(String.class, StringConverter.INSTANCE);
        converters.put(Boolean.class, BooleanConverter.INSTANCE);
        converters.put(boolean.class, BooleanConverter.INSTANCE);
        converters.put(Double.class, DoubleConverter.INSTANCE);
        converters.put(double.class, DoubleConverter.INSTANCE);
        converters.put(Float.class, FloatConverter.INSTANCE);
        converters.put(float.class, FloatConverter.INSTANCE);
        converters.put(Integer.class, IntegerConverter.INSTANCE);
        converters.put(int.class, IntegerConverter.INSTANCE);
        converters.put(Long.class, LongConverter.INSTANCE);
        converters.put(long.class, LongConverter.INSTANCE);

        converters.put(Duration.class, DurationConverter.INSTANCE);
        converters.put(LocalTime.class, LocalTimeConverter.INSTANCE);
        converters.put(LocalDate.class, LocalDateConverter.INSTANCE);
        converters.put(LocalDateTime.class, LocalDateTimeConverter.INSTANCE);
        converters.put(OffsetTime.class, OffsetTimeConverter.INSTANCE);
        converters.put(OffsetDateTime.class, OffsetDateTimeConverter.INSTANCE);
        converters.put(Instant.class, InstantConverter.INSTANCE);

        converters.put(URL.class, URLConverter.INSTANCE);
    }


    @Override
    public <T> Optional<T> getOptionalValue(String propertyName, Class<T> asType) {
        String value = getValue(propertyName);
        if (value != null && value.length() == 0) {
            // treat an empty string as not existing
            value = null;
        }
        return Optional.ofNullable(convert(value, asType));
    }

    @Override
    public <T> T getValue(String propertyName, Class<T> propertyType) {
        String value = getValue(propertyName);
        if (value == null || value.isEmpty()) {
            throw new NoSuchElementException("No configured value found for config key " + propertyName);
        }

        return convert(value, propertyType);
    }

    public String getValue(String key) {
        for (ConfigSource configSource : configSources) {
            String value = configSource.getValue(key);

            if (value != null) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, "found value {0} for key {1} in ConfigSource {2}.",
                            new Object[]{value, key, configSource.getName()});
                }

                return value;
            }
        }

        return null;
    }

    public <T> T convert(String value, Class<T> asType) {
        if (value != null) {
            Converter<T> converter = getConverter(asType);
            return converter.convert(value);
        }

        return null;
    }

    private <T> Converter getConverter(Class<T> asType) {
        Converter converter = converters.get(asType);
        if (converter == null) {
            throw new IllegalArgumentException("No Converter registered for class " + asType);
        }
        return converter;
    }

    public ConfigValueImpl<String> access(String key) {
        return new ConfigValueImpl<String>(this, key);
    }

    @Override
    public Iterable<String> getPropertyNames() {
        Set<String> result = new HashSet<>();

        for (ConfigSource configSource : configSources) {
            result.addAll(configSource.getProperties().keySet());

        }
        return result;
    }



    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return Collections.unmodifiableList(configSources);
    }

    public synchronized void addConfigSources(List<ConfigSource> configSourcesToAdd) {
        List<ConfigSource> allConfigSources = new ArrayList<>(configSources);
        allConfigSources.addAll(configSourcesToAdd);

        // finally put all the configSources back into the map
        configSources = sortDescending(allConfigSources);
    }


    public synchronized void addConverter(Converter<?> converter) {
        if (converter == null) {
            return;
        }

        Type targetType = getTypeOfConverter(converter.getClass());
        if (targetType == null ) {
            throw new IllegalStateException("Converter " + converter.getClass() + " must be a ParameterisedType");
        }

        Converter oldConverter = converters.get(targetType);
        if (oldConverter == null || getPriority(converter) > getPriority(oldConverter)) {
            converters.put(targetType, converter);
        }
    }

    private int getPriority(Converter<?> converter) {
        int priority = 100;
        Priority priorityAnnotation = converter.getClass().getAnnotation(Priority.class);
        if (priorityAnnotation != null) {
            priority = priorityAnnotation.value();
        }
        return priority;
    }


    public Map<Type, Converter> getConverters() {
        return converters;
    }


    protected List<ConfigSource> sortDescending(List<ConfigSource> configSources) {
        Collections.sort(configSources, new Comparator<ConfigSource>() {
            @Override
            public int compare(ConfigSource configSource1, ConfigSource configSource2) {
                return (configSource1.getOrdinal() > configSource2.getOrdinal()) ? -1 : 1;
            }
        });
        return configSources;

    }

    private Type getTypeOfConverter(Class clazz) {
        if (clazz.equals(Object.class)) {
            return null;
        }

        Type[] genericInterfaces = clazz.getGenericInterfaces();
        for (Type genericInterface : genericInterfaces) {
            if (genericInterface instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) genericInterface;
                if (pt.getRawType().equals(Converter.class)) {
                    Type[] typeArguments = pt.getActualTypeArguments();
                    if (typeArguments.length != 1) {
                        throw new IllegalStateException("Converter " + clazz + " must be a ParameterisedType");
                    }
                    return typeArguments[0];
                }
            }
        }

        return getTypeOfConverter(clazz.getSuperclass());
    }
}