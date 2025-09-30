/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.flowlogix.maven.plugins;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.experimental.Delegate;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * Watches a directory and its subdirectories for file changes.
 */
@RequiredArgsConstructor
class Watcher {
    @Delegate
    private final CommonDevMojo mojo;

    @SneakyThrows({IOException.class, InterruptedException.class})
    public void watch(Path root, @NonNull Consumer<Set<Path>> onChange) {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            Map<WatchKey, Path> keys = new ConcurrentHashMap<>();
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isDirectory).forEach(path -> register(path, keys, watchService));
            }
            while (!Thread.interrupted()) {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) {
                    continue;
                }
                Set<Path> modifiedFiles = new HashSet<>();
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path path = keys.get(key).resolve((Path) event.context());
                    getLog().debug("Event kind: " + event.kind() + ". File affected: " + event.context());
                    getLog().debug("key path: " + path);
                    if (event.kind() == ENTRY_CREATE && path.toFile().isDirectory()) {
                        register(path, keys, watchService);
                    }
                    if (path.toFile().isFile()) {
                        modifiedFiles.add(path);
                    }
                }
                key.reset();
                if (!modifiedFiles.isEmpty()) {
                    onChange.accept(modifiedFiles);
                }
            }
            Thread.currentThread().interrupt();
        }
    }

    @SneakyThrows(IOException.class)
    private void register(Path path, Map<WatchKey, Path> keys, WatchService watchService) {
        getLog().debug("Registering path for watch: " + path);
        keys.put(path.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), path);
    }
}
