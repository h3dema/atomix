/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.raft.state;

import net.kuujo.copycat.log.Compaction;
import net.kuujo.copycat.log.Entry;
import net.kuujo.copycat.raft.*;
import net.kuujo.copycat.raft.log.*;
import net.kuujo.copycat.util.concurrent.ComposableFuture;
import net.kuujo.copycat.util.concurrent.Context;
import net.kuujo.copycat.util.concurrent.Futures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Resource state machine.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
class RaftState {
  private static final Logger LOGGER = LoggerFactory.getLogger(RaftState.class);
  private final StateMachine stateMachine;
  private final ClusterState members;
  private final Context context;
  private final SessionManager sessions;
  private final Map<Long, SessionContext> contexts = new HashMap<>();
  private final Map<Long, List<Runnable>> queries = new HashMap<>();
  private long sessionTimeout = 5000;
  private long lastApplied;

  public RaftState(StateMachine stateMachine, ClusterState members, SessionManager sessions, Context context) {
    this.stateMachine = stateMachine;
    this.members = members;
    this.sessions = sessions;
    this.context = context;
  }

  /**
   * Returns the session timeout.
   *
   * @return The session timeout.
   */
  public long getSessionTimeout() {
    return sessionTimeout;
  }

  /**
   * Sets the session timeout.
   *
   * @param sessionTimeout The session timeout.
   * @return The Raft state machine.
   */
  public RaftState setSessionTimeout(long sessionTimeout) {
    if (sessionTimeout <= 0)
      throw new IllegalArgumentException("session timeout must be positive");

    this.sessionTimeout = sessionTimeout;

    return this;
  }

  /**
   * Returns the last index applied to the state machine.
   *
   * @return The last index applied to the state machine.
   */
  public long getLastApplied() {
    return lastApplied;
  }

  /**
   * Sets the last index applied to the state machine.
   *
   * @param lastApplied The last index applied to the state machine.
   */
  private void setLastApplied(long lastApplied) {
    this.lastApplied = lastApplied;

    List<Runnable> queries = this.queries.remove(lastApplied);

    if (queries != null) {
      queries.forEach(Runnable::run);
    }
  }

  /**
   * Filters an entry.
   *
   * @param entry The entry to filter.
   * @return A boolean value indicating whether to keep the entry.
   */
  public CompletableFuture<Boolean> filter(Entry entry, Compaction compaction) {
    if (entry instanceof CommandEntry) {
      return filter((CommandEntry) entry, compaction);
    } else if (entry instanceof KeepAliveEntry) {
      return filter((KeepAliveEntry) entry, compaction);
    } else if (entry instanceof RegisterEntry) {
      return filter((RegisterEntry) entry, compaction);
    } else if (entry instanceof NoOpEntry) {
      return filter((NoOpEntry) entry, compaction);
    } else if (entry instanceof JoinEntry) {
      return filter((JoinEntry) entry, compaction);
    } else if (entry instanceof LeaveEntry) {
      return filter((LeaveEntry) entry, compaction);
    }
    return CompletableFuture.completedFuture(false);
  }

  /**
   * Filters an entry.
   *
   * @param entry The entry to filter.
   * @return A boolean value indicating whether to keep the entry.
   */
  public CompletableFuture<Boolean> filter(RegisterEntry entry, Compaction compaction) {
    return Futures.completedFutureAsync(contexts.containsKey(entry.getIndex()), context);
  }

  /**
   * Filters an entry.
   *
   * @param entry The entry to filter.
   * @return A boolean value indicating whether to keep the entry.
   */
  public CompletableFuture<Boolean> filter(KeepAliveEntry entry, Compaction compaction) {
    return Futures.completedFutureAsync(contexts.containsKey(entry.getIndex()) && contexts.get(entry.getIndex()).index == entry.getIndex(), context);
  }

  /**
   * Filters a no-op entry.
   *
   * @param entry The entry to filter.
   * @return A boolean value indicating whether to keep the entry.
   */
  public CompletableFuture<Boolean> filter(NoOpEntry entry, Compaction compaction) {
    return Futures.completedFutureAsync(false, context);
  }

  /**
   * Filters an entry.
   *
   * @param entry The entry to filter.
   * @return A boolean value indicating whether to keep the entry.
   */
  public CompletableFuture<Boolean> filter(CommandEntry entry, Compaction compaction) {
    Commit<? extends Command> commit = new Commit<>(entry.getIndex(), null, entry.getTimestamp(), entry.getCommand());
    return execute(() -> stateMachine.filter(commit, compaction));
  }

  /**
   * Filters an entry.
   *
   * @param entry The entry to filter.
   * @return A boolean value indicating whether to keep the entry.
   */
  public CompletableFuture<Boolean> filter(JoinEntry entry, Compaction compaction) {
    MemberState member = members.getMember(entry.getMember().id());
    return Futures.completedFutureAsync(member != null && member.getVersion() == entry.getIndex(), context);
  }

  /**
   * Filters an entry.
   *
   * @param entry The entry to filter.
   * @return A boolean value indicating whether to keep the entry.
   */
  public CompletableFuture<Boolean> filter(LeaveEntry entry, Compaction compaction) {
    return Futures.completedFutureAsync(!compaction.type().isOrdered(), context);
  }

  /**
   * Applies an entry to the state machine.
   *
   * @param entry The entry to apply.
   * @return The result.
   */
  public CompletableFuture<?> apply(Entry entry) {
    if (entry instanceof CommandEntry) {
      return apply((CommandEntry) entry);
    } else if (entry instanceof QueryEntry) {
      return apply((QueryEntry) entry);
    } else if (entry instanceof RegisterEntry) {
      return apply((RegisterEntry) entry);
    } else if (entry instanceof KeepAliveEntry) {
      return apply((KeepAliveEntry) entry);
    } else if (entry instanceof NoOpEntry) {
      return apply((NoOpEntry) entry);
    } else if (entry instanceof JoinEntry) {
      return apply((JoinEntry) entry);
    } else if (entry instanceof LeaveEntry) {
      return apply((LeaveEntry) entry);
    }
    return Futures.exceptionalFuture(new InternalException("unknown state machine operation"));
  }

  /**
   * Applies an entry to the state machine.
   *
   * @param entry The entry to apply.
   * @return The result.
   */
  public CompletableFuture<Long> apply(RegisterEntry entry) {
    return register(entry.getIndex(), entry.getMember(), entry.getConnection(), entry.getTimestamp());
  }

  /**
   * Applies an entry to the state machine.
   *
   * @param entry The entry to apply.
   */
  public CompletableFuture<Void> apply(KeepAliveEntry entry) {
    return keepAlive(entry.getIndex(), entry.getTimestamp(), entry.getSession());
  }

  /**
   * Applies an entry to the state machine.
   *
   * @param entry The entry to apply.
   * @return The result.
   */
  public CompletableFuture<Object> apply(CommandEntry entry) {
    return command(entry.getIndex(), entry.getSession(), entry.getRequest(), entry.getResponse(), entry.getTimestamp(), entry
      .getCommand());
  }

  /**
   * Applies an entry to the state machine.
   *
   * @param entry The entry to apply.
   * @return The result.
   */
  public CompletableFuture<Object> apply(QueryEntry entry) {
    return query(entry.getIndex(), entry.getSession(), entry.getVersion(), entry.getTimestamp(), entry.getQuery());
  }

  /**
   * Applies an entry to the state machine.
   *
   * @param entry The entry to apply.
   * @return The result.
   */
  public CompletableFuture<Long> apply(NoOpEntry entry) {
    return noop(entry.getIndex());
  }

  /**
   * Applies an entry to the state machine.
   *
   * @param entry The entry to apply.
   * @return The result.
   */
  public CompletableFuture<Long> apply(JoinEntry entry) {
    return join(entry.getIndex(), entry.getMember());
  }

  /**
   * Applies an entry to the state machine.
   *
   * @param entry The entry to apply.
   * @return The result.
   */
  public CompletableFuture<Void> apply(LeaveEntry entry) {
    return leave(entry.getIndex(), entry.getMember());
  }

  /**
   * Registers a member session.
   *
   * @param index The registration index.
   * @param memberId The session member ID.
   * @param connectionId the session connection ID.
   * @param timestamp The registration timestamp.
   * @return The session ID.
   */
  private CompletableFuture<Long> register(long index, int memberId, UUID connectionId, long timestamp) {
    Session session = sessions.registerSession(index, memberId, connectionId);

    SessionContext context = new SessionContext(timestamp);
    contexts.put(index, context);

    if (memberId != 0) {
      MemberState state = members.getMember(memberId);
      if (state != null) {
        state.setSession(index);
      }
    }

    // We need to ensure that the command is applied to the state machine before queries are run.
    // Set last applied only after the operation has been submitted to the state machine executor.
    CompletableFuture<Long> future = CompletableFuture.supplyAsync(() -> {
      stateMachine.register(session);
      return session.id();
    }, this.context);

    setLastApplied(index);
    return future;
  }

  /**
   * Keeps a member session alive.
   *
   * @param index The keep alive index.
   * @param timestamp The keep alive timestamp.
   * @param sessionId The session to keep alive.
   */
  private CompletableFuture<Void> keepAlive(long index, long timestamp, long sessionId) {
    SessionContext context = contexts.get(sessionId);

    // We need to ensure that the command is applied to the state machine before queries are run.
    // Set last applied only after the operation has been submitted to the state machine executor.
    CompletableFuture<Void> future;
    if (context == null) {
      LOGGER.warn("Unknown session: " + sessionId);
      future = Futures.exceptionalFutureAsync(new UnknownSessionException("unknown session: " + sessionId), this.context);
    } else if (!context.update(index, timestamp)) {
      LOGGER.warn("Expired session: " + sessionId);
      expireSession(sessionId);
      future = Futures.exceptionalFutureAsync(new UnknownSessionException("session expired: " + sessionId), this.context);
    } else {
      future = Futures.completedFutureAsync(null, this.context);
    }

    setLastApplied(index);
    return future;
  }

  /**
   * Applies a no-op to the state machine.
   *
   * @param index The no-op index.
   * @return The no-op index.
   */
  private CompletableFuture<Long> noop(long index) {
    // We need to ensure that the command is applied to the state machine before queries are run.
    // Set last applied only after the operation has been submitted to the state machine executor.
    CompletableFuture<Long> future = Futures.completedFutureAsync(index, context);
    setLastApplied(index);
    return future;
  }

  /**
   * Applies a command to the state machine.
   *
   * @param index The command index.
   * @param sessionId The command session ID.
   * @param request The command request ID.
   * @param response The command response ID.
   * @param timestamp The command timestamp.
   * @param command The command to apply.
   * @return The command result.
   */
  @SuppressWarnings("unchecked")
  private CompletableFuture<Object> command(long index, long sessionId, long request, long response, long timestamp, Command command) {
    final CompletableFuture<Object> future;

    // First check to ensure that the session exists.
    SessionContext context = contexts.get(sessionId);
    if (context == null) {
      LOGGER.warn("Unknown session: " + sessionId);
      future = Futures.exceptionalFutureAsync(new UnknownSessionException("unknown session " + sessionId), this.context);
    } else if (!context.update(index, timestamp)) {
      LOGGER.warn("Expired session: " + sessionId);
      expireSession(sessionId);
      future = Futures.exceptionalFutureAsync(new UnknownSessionException("unknown session " + sessionId), this.context);
    } else if (context.responses.containsKey(request)) {
      future = CompletableFuture.completedFuture(context.responses.get(request));
    } else {
      // Apply the command to the state machine.
      ServerSession session = sessions.getSession(sessionId);

      future = execute(() -> stateMachine.apply(new Commit(index, session, timestamp, command)))
        .thenApply(result -> {
          // Store the command result in the session.
          context.responses.put(request, result);

          // Clear any responses that have been received by the client for the session.
          context.responses.headMap(response, true).clear();
          return result;
        });
    }

    // We need to ensure that the command is applied to the state machine before queries are run.
    // Set last applied only after the operation has been submitted to the state machine executor.
    setLastApplied(index);

    return future;
  }

  /**
   * Applies a query to the state machine.
   *
   * @param index The query index.
   * @param sessionId The query session ID.
   * @param version The request version.
   * @param timestamp The query timestamp.
   * @param query The query to apply.
   * @return The query result.
   */
  @SuppressWarnings("unchecked")
  private CompletableFuture<Object> query(long index, long sessionId, long version, long timestamp, Query query) {
    // If the session has not yet been opened or if the client provided a version greater than the last applied index
    // then wait until the up-to-date index is applied to the state machine.
    if (sessionId > lastApplied || version > lastApplied) {
      CompletableFuture<Object> future = new CompletableFuture<>();
      ServerSession session = sessions.getSession(sessionId);

      List<Runnable> queries = this.queries.computeIfAbsent(Math.max(sessionId, version), id -> new ArrayList<>());
      queries.add(() -> {
        execute(() -> stateMachine.apply(new Commit(index, session, timestamp, query)), future);
      });

      return future;
    } else {

      // Verify that the client's session is still alive.
      SessionContext context = contexts.get(sessionId);
      if (context == null) {
        LOGGER.warn("Unknown session: " + sessionId);
        return Futures.exceptionalFutureAsync(new UnknownSessionException("unknown session: " + sessionId), this.context);
      } else if (!context.expire(timestamp)) {
        LOGGER.warn("Expired session: " + sessionId);
        expireSession(sessionId);
        return Futures.exceptionalFutureAsync(new UnknownSessionException("unknown session: " + sessionId), this.context);
      } else {
        ServerSession session = sessions.getSession(sessionId);
        return execute(() -> stateMachine.apply(new Commit(index, session, timestamp, query)));
      }
    }
  }

  /**
   * Expires a session.
   */
  private void expireSession(long sessionId) {
    contexts.remove(sessionId);
    ServerSession session = sessions.getSession(sessionId);
    if (session != null) {
      if (session.member() != 0) {
        MemberState state = members.getMember(session.member());
        if (state != null) {
          state.setSession(0);
        }
      }
      this.context.execute(() -> stateMachine.expire(session));
    }
  }

  /**
   * Applies a join to the state machine.
   *
   * @param index The join index.
   * @param member The joining member.
   * @return A completable future to be completed with the join index.
   */
  private CompletableFuture<Long> join(long index, Member member) {
    MemberState state = members.getMember(member.id());

    if (state == null) {
      state = new MemberState(member.id(), Member.Type.PASSIVE, System.currentTimeMillis()).setVersion(index);
      members.addMember(state);
    }

    setLastApplied(index);

    return Futures.completedFutureAsync(index, context);
  }

  /**
   * Applies a leave to the state machine.
   *
   * @param index The leave index.
   * @param member The leaving member.
   * @return A completable future to be completed once the member has been removed.
   */
  private CompletableFuture<Void> leave(long index, Member member) {
    MemberState state = members.getMember(member.id());

    if (state != null) {
      members.removeMember(state);
    }

    setLastApplied(index);

    return Futures.completedFutureAsync(null, context);
  }

  /**
   * Executes a method in the state machine thread and completes the given future asynchronously in the same thread.
   */
  private <T> CompletableFuture<T> execute(Supplier<CompletableFuture<T>> supplier) {
    return execute(supplier, new ComposableFuture<T>());
  }

  /**
   * Executes a method in the state machine thread and completes the given future asynchronously in the same thread.
   */
  private <T> CompletableFuture<T> execute(Supplier<CompletableFuture<T>> supplier, CompletableFuture<T> future) {
    context.execute(() -> {
      supplier.get().whenCompleteAsync((result, error) -> {
        if (error == null) {
          future.complete(result);
        } else {
          future.completeExceptionally(error);
        }
      }, context);
    });
    return future;
  }

  /**
   * Session context.
   */
  private class SessionContext {
    private long index;
    private long timestamp;
    private final TreeMap<Long, Object> responses = new TreeMap<>();

    private SessionContext(long timestamp) {
      this.timestamp = timestamp;
    }

    /**
     * Returns the session timestamp.
     *
     * @return The session timestamp.
     */
    public long timestamp() {
      return timestamp;
    }

    /**
     * Updates the session.
     *
     * @param timestamp The session.
     */
    private boolean expire(long timestamp) {
      if (timestamp - sessionTimeout > this.timestamp) {
        return false;
      }
      this.timestamp = timestamp;
      return true;
    }

    /**
     * Updates the session.
     *
     * @param timestamp The session.
     */
    private boolean update(long index, long timestamp) {
      if (timestamp - sessionTimeout > this.timestamp) {
        return false;
      }
      this.index = index;
      this.timestamp = timestamp;
      return true;
    }
  }

}