/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.protocol;

import net.kuujo.copycat.EventListener;
import net.kuujo.copycat.util.concurrent.Futures;
import net.kuujo.copycat.util.concurrent.NamedThreadFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Local protocol server implementation.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class LocalProtocolServer implements ProtocolServer {
  private final Executor executor = Executors.newSingleThreadExecutor(new NamedThreadFactory("copycat-protocol-thread-%d"));
  private final String address;
  private final Map<String, LocalProtocolServer> registry;
  private EventListener<ProtocolConnection> listener;

  public LocalProtocolServer(String address, Map<String, LocalProtocolServer> registry) {
    this.address = address;
    this.registry = registry;
  }

  CompletableFuture<ProtocolConnection> connect() {
    if (listener != null) {
      LocalProtocolConnection connection = new LocalProtocolConnection();
      listener.accept(connection);
      return CompletableFuture.completedFuture(connection);
    } else {
      return Futures.exceptionalFuture(new ProtocolException("No server found"));
    }
  }

  @Override
  public ProtocolServer connectListener(EventListener<ProtocolConnection> listener) {
    this.listener = listener;
    return this;
  }

  @Override
  public CompletableFuture<Void> listen() {
    return CompletableFuture.supplyAsync(() -> {
      registry.put(address, this);
      return null;
    }, executor);
  }

  @Override
  public CompletableFuture<Void> close() {
    return CompletableFuture.supplyAsync(() -> {
      registry.remove(address);
      return null;
    }, executor);
  }

}
