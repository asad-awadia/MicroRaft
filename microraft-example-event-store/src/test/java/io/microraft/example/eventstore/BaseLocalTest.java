/*
 * Copyright (c) 2020, MicroRaft.
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

package io.microraft.example.eventstore;

import io.microraft.RaftConfig;
import io.microraft.RaftEndpoint;
import io.microraft.RaftNode;
import io.microraft.RaftNodeStatus;
import io.microraft.model.impl.DefaultRaftModelFactory;
import io.microraft.report.RaftGroupTerm;
import io.microraft.statemachine.StateMachine;
import org.junit.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.*;

/**
 * Base test class for local tests. Provides method to simulate different behaviors in distributed system.
 */
public abstract class BaseLocalTest {

    protected List<RaftEndpoint> initialMembers = Arrays
            .asList(LocalRaftEndpoint.newEndpoint(), LocalRaftEndpoint.newEndpoint(), LocalRaftEndpoint.newEndpoint());
    protected List<LocalRaftNodeRuntime> runtimes = new ArrayList<>();
    protected List<RaftNode> raftNodes = new ArrayList<>();

    public static void eventually(AssertTask task) {
        AssertionError error = null;
        long timeoutSeconds = 30;
        long sleepMillis = 200;
        long iterations = TimeUnit.SECONDS.toMillis(timeoutSeconds) / sleepMillis;
        for (int i = 0; i < iterations; i++) {
            try {
                try {
                    task.run();
                    return;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } catch (AssertionError e) {
                error = e;
            }

            sleepMillis(sleepMillis);
        }

        if (error != null) {
            throw error;
        }

        fail("eventually() failed without AssertionError!");
    }

    public static void sleepMillis(long millis) {
        try {
            MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Before
    public void startRaftGroup() {
        for (RaftEndpoint endpoint : initialMembers) {
            RaftNode raftNode = createRaftNode(endpoint);
            raftNode.start();
        }
    }

    @After
    public void terminateRaftGroup() {
        raftNodes.forEach(RaftNode::terminate);
    }

    protected RaftConfig getConfig() {
        return RaftConfig.DEFAULT_RAFT_CONFIG;
    }

    protected abstract StateMachine createStateMachine();

    protected RaftNode createRaftNode(RaftEndpoint endpoint) {
        RaftConfig config = getConfig();
        LocalRaftNodeRuntime runtime = new LocalRaftNodeRuntime(endpoint);
        StateMachine stateMachine = createStateMachine();
        RaftNode raftNode = RaftNode.newBuilder().setGroupId("default").setLocalEndpoint(endpoint)
                                    .setInitialGroupMembers(initialMembers).setConfig(config).setRuntime(runtime)
                                    .setStateMachine(stateMachine).setModelFactory(DefaultRaftModelFactory.INSTANCE).build();

        raftNodes.add(raftNode);
        runtimes.add(runtime);
        enableDiscovery(raftNode, runtime);

        return raftNode;
    }

    protected final void enableDiscovery(RaftNode raftNode, LocalRaftNodeRuntime runtime) {
        for (int i = 0; i < raftNodes.size(); i++) {
            RaftNode otherNode = raftNodes.get(i);
            if (otherNode != raftNode) {
                runtimes.get(i).discoverNode(raftNode);
                runtime.discoverNode(otherNode);
            }
        }
    }

    protected final RaftNode waitUntilLeaderElected() {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60);
        while (System.currentTimeMillis() < deadline) {
            RaftEndpoint leaderEndpoint = getLeaderEndpoint();
            if (leaderEndpoint != null) {
                return raftNodes.stream().filter(node -> node.getLocalEndpoint().equals(leaderEndpoint)).findFirst()
                                .orElseThrow(IllegalStateException::new);
            }

            sleepMillis(10);
        }

        throw new AssertionError("Could not elect a leader on time!");
    }

    private RaftEndpoint getLeaderEndpoint() {
        RaftEndpoint leaderEndpoint = null;
        int leaderTerm = 0;
        for (RaftNode raftNode : raftNodes) {
            if (raftNode.getStatus() == RaftNodeStatus.TERMINATED) {
                continue;
            }

            RaftGroupTerm term = raftNode.getTerm();
            if (term.getLeaderEndpoint() != null) {
                if (leaderEndpoint == null) {
                    leaderEndpoint = term.getLeaderEndpoint();
                    leaderTerm = term.getTerm();
                } else if (!(leaderEndpoint.equals(term.getLeaderEndpoint()) && leaderTerm == term.getTerm())) {
                    leaderEndpoint = null;
                    break;
                }
            } else {
                leaderEndpoint = null;
                break;
            }
        }

        return leaderEndpoint;
    }

    protected final RaftNode getAnyNodeExcept(RaftEndpoint endpoint) {
        requireNonNull(endpoint);

        return raftNodes.stream().filter(raftNode -> !raftNode.getLocalEndpoint().equals(endpoint)).findFirst()
                        .orElseThrow(IllegalArgumentException::new);
    }

    protected final void disconnect(RaftEndpoint endpoint1, RaftEndpoint endpoint2) {
        requireNonNull(endpoint1);
        requireNonNull(endpoint2);

        getRuntime(endpoint1).undiscoverNode(getNode(endpoint2));
        getRuntime(endpoint2).undiscoverNode(getNode(endpoint1));
    }

    protected final void connect(RaftEndpoint endpoint1, RaftEndpoint endpoint2) {
        requireNonNull(endpoint1);
        requireNonNull(endpoint2);

        getRuntime(endpoint1).discoverNode(getNode(endpoint2));
        getRuntime(endpoint2).discoverNode(getNode(endpoint1));
    }

    private RaftNode getNode(RaftEndpoint endpoint) {
        requireNonNull(endpoint);

        return raftNodes.stream().filter(raftNode -> raftNode.getLocalEndpoint().equals(endpoint)).findFirst()
                        .orElseThrow(IllegalArgumentException::new);
    }

    private LocalRaftNodeRuntime getRuntime(RaftEndpoint endpoint) {
        requireNonNull(endpoint);

        return runtimes.stream().filter(runtime -> runtime.getLocalEndpoint().equals(endpoint)).findFirst()
                       .orElseThrow(IllegalArgumentException::new);
    }

    @FunctionalInterface
    public interface AssertTask {
        void run()
                throws Exception;
    }

}
