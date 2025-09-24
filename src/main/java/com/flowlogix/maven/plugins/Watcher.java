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
        }
    }

    @SneakyThrows(IOException.class)
    private void register(Path path, Map<WatchKey, Path> keys, WatchService watchService) {
        getLog().debug("Registering path for watch: " + path);
        keys.put(path.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), path);
    }
}
