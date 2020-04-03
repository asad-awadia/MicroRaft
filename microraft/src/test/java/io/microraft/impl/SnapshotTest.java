/*
 * Original work Copyright (c) 2008-2020, Hazelcast, Inc.
 * Modified work Copyright 2020, MicroRaft.
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

package io.microraft.impl;

import io.microraft.Ordered;
import io.microraft.RaftConfig;
import io.microraft.RaftNode;
import io.microraft.exception.IndeterminateStateException;
import io.microraft.impl.local.LocalRaftGroup;
import io.microraft.impl.local.SimpleStateMachine;
import io.microraft.impl.log.SnapshotChunkCollector;
import io.microraft.impl.model.groupop.DefaultUpdateRaftGroupMembersOpOrBuilder;
import io.microraft.impl.model.message.DefaultAppendEntriesRequestOrBuilder;
import io.microraft.impl.util.BaseTest;
import io.microraft.model.log.LogEntry;
import io.microraft.model.message.AppendEntriesFailureResponse;
import io.microraft.model.message.AppendEntriesRequest;
import io.microraft.model.message.AppendEntriesSuccessResponse;
import io.microraft.model.message.InstallSnapshotRequest;
import io.microraft.model.message.InstallSnapshotResponse;
import io.microraft.model.message.RaftMessage;
import io.microraft.report.RaftGroupMembers;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;

import static io.microraft.MembershipChangeMode.ADD;
import static io.microraft.RaftNodeStatus.ACTIVE;
import static io.microraft.impl.local.SimpleStateMachine.apply;
import static io.microraft.impl.util.AssertionUtils.eventually;
import static io.microraft.impl.util.RaftTestUtils.getCommitIndex;
import static io.microraft.impl.util.RaftTestUtils.getCommittedGroupMembers;
import static io.microraft.impl.util.RaftTestUtils.getLastLogOrSnapshotEntry;
import static io.microraft.impl.util.RaftTestUtils.getMatchIndex;
import static io.microraft.impl.util.RaftTestUtils.getSnapshotChunkCollector;
import static io.microraft.impl.util.RaftTestUtils.getSnapshotEntry;
import static io.microraft.impl.util.RaftTestUtils.getStatus;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

/**
 * @author mdogan
 * @author metanet
 */
public class SnapshotTest
        extends BaseTest {

    private LocalRaftGroup group;

    @After
    public void destroy() {
        if (group != null) {
            group.destroy();
        }
    }

    @Test(timeout = 300_000)
    public void when_commitLogAdvances_then_snapshotIsTaken() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).build();
        group = new LocalRaftGroup(3, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();

        for (int i = 0; i < entryCount; i++) {
            leader.replicate(apply("val" + i)).join();
        }

        eventually(() -> {
            for (RaftNodeImpl raftNode : group.getNodes()) {
                assertThat(getCommitIndex(raftNode)).isEqualTo(entryCount);
                assertThat(getSnapshotEntry(raftNode).getIndex()).isEqualTo(entryCount);

                SimpleStateMachine stateMachine = group.getRuntime(raftNode.getLocalEndpoint()).getStateMachine();
                assertThat(stateMachine.size()).isEqualTo(entryCount);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_snapshotIsTaken_then_nextEntryIsCommitted() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).build();
        group = new LocalRaftGroup(3, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();

        for (int i = 0; i < entryCount; i++) {
            leader.replicate(apply("val" + i)).join();
        }

        eventually(() -> {
            for (RaftNodeImpl raftNode : group.getNodes()) {
                assertThat(getCommitIndex(raftNode)).isEqualTo(entryCount);
                assertThat(getSnapshotEntry(raftNode).getIndex()).isEqualTo(entryCount);
            }
        });

        leader.replicate(apply("valFinal")).join();

        eventually(() -> {
            for (RaftNodeImpl raftNode : group.getNodes()) {
                assertThat(getCommitIndex(raftNode)).isEqualTo(entryCount + 1);
                SimpleStateMachine stateMachine = group.getRuntime(raftNode.getLocalEndpoint()).getStateMachine();
                assertThat(stateMachine.size()).isEqualTo(entryCount + 1);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
                assertThat(stateMachine.get(51)).isEqualTo("valFinal");
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_followersMatchIndexIsUnknown_then_itInstallsSnapshotFromLeaderOnly() {
        when_followersMatchIndexIsUnknown_then_itInstallsSnapshot(false);
    }

    @Test(timeout = 300_000)
    public void when_followersMatchIndexIsUnknown_then_itInstallsSnapshotFromLeaderAndFollowers() {
        when_followersMatchIndexIsUnknown_then_itInstallsSnapshot(true);
    }

    private void when_followersMatchIndexIsUnknown_then_itInstallsSnapshot(boolean transferSnapshotFromFollowersEnabled) {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount)
                                      .setTransferSnapshotsFromFollowersEnabled(transferSnapshotFromFollowersEnabled).build();
        group = new LocalRaftGroup(3, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();

        RaftNodeImpl[] followers = group.getNodesExcept(leader.getLocalEndpoint());
        RaftNodeImpl slowFollower = followers[1];

        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);

        for (int i = 0; i < entryCount; i++) {
            leader.replicate(apply("val" + i)).join();
        }

        eventually(() -> assertThat(getSnapshotEntry(leader).getIndex()).isEqualTo(entryCount));

        leader.replicate(apply("valFinal")).join();

        group.allowMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);
        group.allowMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> assertThat(getCommitIndex(slowFollower)).isEqualTo(getCommitIndex(leader)));

        group.resetAllRulesFrom(leader.getLocalEndpoint());

        eventually(() -> {
            for (RaftNodeImpl raftNode : group.getNodes()) {
                assertThat(getCommitIndex(raftNode)).isEqualTo(entryCount + 1);
                SimpleStateMachine stateMachine = group.getRuntime(raftNode.getLocalEndpoint()).getStateMachine();
                assertThat(stateMachine.size()).isEqualTo(entryCount + 1);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
                assertThat(stateMachine.get(51)).isEqualTo("valFinal");
            }
        });

        assertThat(getSnapshotChunkCollector(slowFollower)).isNull();
    }

    @Test(timeout = 300_000)
    public void when_leaderKnowsOthersSnapshot_then_slowFollowerInstallsSnapshotFromLeaderWithoutOptimization() {
        when_leaderKnowsOtherFollowerInstalledSnapshot_then_slowFollowerInstallsSnapshot(false);
    }

    @Test(timeout = 300_000)
    public void when_leaderKnowsOthersSnapshot_then_slowFollowerInstallsSnapshotFromAllWithOptimization() {
        when_leaderKnowsOtherFollowerInstalledSnapshot_then_slowFollowerInstallsSnapshot(true);
    }

    private void when_leaderKnowsOtherFollowerInstalledSnapshot_then_slowFollowerInstallsSnapshot(
            boolean transferSnapshotFromFollowersEnabled) {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount)
                                      .setTransferSnapshotsFromFollowersEnabled(transferSnapshotFromFollowersEnabled).build();
        group = new LocalRaftGroup(3, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();

        leader.replicate(apply("val0")).join();

        eventually(() -> {
            for (RaftNodeImpl follower : group.getNodesExcept(leader.getLocalEndpoint())) {
                assertThat(getCommitIndex(follower)).isEqualTo(getCommitIndex(leader));
            }
        });

        RaftNodeImpl[] followers = group.getNodesExcept(leader.getLocalEndpoint());
        RaftNodeImpl slowFollower = followers[1];

        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);

        for (int i = 1; i < entryCount; i++) {
            leader.replicate(apply("val" + i)).join();
        }

        eventually(() -> assertThat(getSnapshotEntry(leader).getIndex()).isEqualTo(entryCount));

        leader.replicate(apply("valFinal")).join();

        group.allowMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);
        group.allowMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> assertThat(getCommitIndex(slowFollower)).isEqualTo(getCommitIndex(leader)));

        group.resetAllRulesFrom(leader.getLocalEndpoint());

        eventually(() -> {
            for (RaftNodeImpl raftNode : group.getNodes()) {
                assertThat(getCommitIndex(raftNode)).isEqualTo(entryCount + 1);
                SimpleStateMachine stateMachine = group.getRuntime(raftNode.getLocalEndpoint()).getStateMachine();
                assertThat(stateMachine.size()).isEqualTo(entryCount + 1);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
                assertThat(stateMachine.get(51)).isEqualTo("valFinal");
            }
        });

        assertThat(getSnapshotChunkCollector(slowFollower)).isNull();
    }

    @Test(timeout = 300_000)
    public void when_leaderDoesNotKnowOthersSnapshot_then_slowFollowerInstallsSnapshotFromLeaderOnlyWithoutOptimization() {
        when_leaderDoesNotKnowOtherFollowerInstalledSnapshot_then_slowFollowerInstallsSnapshotFromLeaderOnly(false);
    }

    @Test(timeout = 300_000)
    public void when_leaderDoesNotKnowOthersSnapshot_then_slowFollowerInstallsSnapshotFromLeaderOnlyWithOptimization() {
        when_leaderDoesNotKnowOtherFollowerInstalledSnapshot_then_slowFollowerInstallsSnapshotFromLeaderOnly(true);
    }

    private void when_leaderDoesNotKnowOtherFollowerInstalledSnapshot_then_slowFollowerInstallsSnapshotFromLeaderOnly(
            boolean transferSnapshotFromFollowersEnabled) {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount)
                                      .setTransferSnapshotsFromFollowersEnabled(transferSnapshotFromFollowersEnabled).build();
        group = new LocalRaftGroup(3, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();

        RaftNodeImpl[] followers = group.getNodesExcept(leader.getLocalEndpoint());
        RaftNodeImpl fastFollower = followers[0];
        RaftNodeImpl slowFollower = followers[1];

        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);

        while (getSnapshotEntry(leader).getIndex() == 0) {
            leader.replicate(apply("val")).join();
        }

        eventually(() -> assertThat(getSnapshotEntry(leader).getIndex()).isGreaterThan(0));
        eventually(() -> assertThat(getSnapshotEntry(fastFollower).getIndex()).isGreaterThan(0));

        group.allowMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);
        group.allowMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> assertThat(getCommitIndex(slowFollower)).isEqualTo(entryCount));

        group.resetAllRulesFrom(leader.getLocalEndpoint());

        eventually(() -> {
            for (RaftNodeImpl raftNode : group.getNodes()) {
                assertThat(getCommitIndex(raftNode)).isEqualTo(getCommitIndex(leader));
                SimpleStateMachine stateMachine = group.getRuntime(raftNode.getLocalEndpoint()).getStateMachine();
                assertThat(stateMachine.size()).isEqualTo(entryCount);
            }
        });

        assertThat(getSnapshotChunkCollector(slowFollower)).isNull();
    }

    @Test(timeout = 300_000)
    public void when_leaderMissesResponse_then_itAdvancesMatchIndexWithNextResponse_leaderOnly() {
        when_leaderMissesInstallSnapshotResponse_then_itAdvancesMatchIndexWithNextInstallSnapshotResponse(false);
    }

    @Test(timeout = 300_000)
    public void when_leaderMissesResponse_then_itAdvancesMatchIndexWithNextResponse_leaderAndFollowers() {
        when_leaderMissesInstallSnapshotResponse_then_itAdvancesMatchIndexWithNextInstallSnapshotResponse(true);
    }

    private void when_leaderMissesInstallSnapshotResponse_then_itAdvancesMatchIndexWithNextInstallSnapshotResponse(
            boolean transferSnapshotFromFollowersEnabled) {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount)
                                      .setTransferSnapshotsFromFollowersEnabled(transferSnapshotFromFollowersEnabled).build();
        group = new LocalRaftGroup(3, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();

        RaftNodeImpl[] followers = group.getNodesExcept(leader.getLocalEndpoint());
        RaftNodeImpl slowFollower = followers[1];

        // the leader cannot send AppendEntriesRPC to the follower
        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);

        // the follower cannot send append response to the leader after installing the snapshot
        group.dropMessagesToMember(slowFollower.getLocalEndpoint(), leader.getLocalEndpoint(),
                                   AppendEntriesSuccessResponse.class);

        for (int i = 0; i < entryCount; i++) {
            leader.replicate(apply("val" + i)).join();
        }

        eventually(() -> assertThat(getSnapshotEntry(leader).getIndex()).isEqualTo(entryCount));

        leader.replicate(apply("valFinal")).join();

        group.resetAllRulesFrom(leader.getLocalEndpoint());

        eventually(() -> {
            for (RaftNodeImpl raftNode : group.getNodesExcept(slowFollower.getLocalEndpoint())) {
                assertThat(getCommitIndex(raftNode)).isEqualTo(entryCount + 1);
                SimpleStateMachine stateMachine = group.getRuntime(raftNode.getLocalEndpoint()).getStateMachine();
                assertThat(stateMachine.size()).isEqualTo(entryCount + 1);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
                assertThat(stateMachine.get(51)).isEqualTo("valFinal");
            }

            assertThat(getCommitIndex(slowFollower)).isEqualTo(entryCount);
            SimpleStateMachine service = group.getRuntime(slowFollower.getLocalEndpoint()).getStateMachine();
            assertThat(service.size()).isEqualTo(entryCount);
            for (int i = 0; i < entryCount; i++) {
                assertThat(service.get(i + 1)).isEqualTo("val" + i);
            }
        });

        group.resetAllRulesFrom(slowFollower.getLocalEndpoint());

        long commitIndex = getCommitIndex(leader);

        eventually(() -> {
            for (RaftNode raftNode : group.getNodesExcept(leader.getLocalEndpoint())) {
                assertThat(getMatchIndex(leader, raftNode.getLocalEndpoint())).isEqualTo(commitIndex);
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_leaderMissesInstallSnapshotResponses_then_followerInstallsSnapshotsViaOtherFollowers() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).build();
        group = new LocalRaftGroup(3, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();

        leader.replicate(apply("val0")).join();

        RaftNodeImpl[] followers = group.getNodesExcept(leader.getLocalEndpoint());
        RaftNodeImpl slowFollower = followers[1];

        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);
        group.dropMessagesToMember(slowFollower.getLocalEndpoint(), leader.getLocalEndpoint(), InstallSnapshotResponse.class);

        for (int i = 1; i < entryCount; i++) {
            leader.replicate(apply("val" + i)).join();
        }

        eventually(() -> assertThat(getSnapshotEntry(leader).getIndex()).isEqualTo(entryCount));

        leader.replicate(apply("valFinal")).join();

        group.allowMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> assertThat(getCommitIndex(slowFollower)).isEqualTo(entryCount));

        group.resetAllRulesFrom(leader.getLocalEndpoint());

        eventually(() -> {
            for (RaftNodeImpl raftNode : group.getNodes()) {
                assertThat(getCommitIndex(raftNode)).isEqualTo(entryCount + 1);
                SimpleStateMachine stateMachine = group.getRuntime(raftNode.getLocalEndpoint()).getStateMachine();
                assertThat(stateMachine.size()).isEqualTo(entryCount + 1);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
                assertThat(stateMachine.get(51)).isEqualTo("valFinal");
            }
        });

        assertThat(getSnapshotChunkCollector(slowFollower)).isNull();
    }

    @Test(timeout = 300_000)
    public void when_leaderAndSomeFollowersMissInstallSnapshotResponses_then_followerInstallsSnapshotsViaOtherFollowers() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).build();
        group = new LocalRaftGroup(5, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();

        leader.replicate(apply("val0")).join();

        RaftNodeImpl[] followers = group.getNodesExcept(leader.getLocalEndpoint());
        RaftNodeImpl slowFollower = followers[0];

        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);
        group.dropMessagesToMember(slowFollower.getLocalEndpoint(), leader.getLocalEndpoint(), InstallSnapshotResponse.class);
        group.dropMessagesToMember(slowFollower.getLocalEndpoint(), followers[2].getLocalEndpoint(),
                                   InstallSnapshotResponse.class);
        group.dropMessagesToMember(slowFollower.getLocalEndpoint(), followers[3].getLocalEndpoint(),
                                   InstallSnapshotResponse.class);

        for (int i = 1; i < entryCount; i++) {
            leader.replicate(apply("val" + i)).join();
        }

        eventually(() -> assertThat(getSnapshotEntry(leader).getIndex()).isEqualTo(entryCount));

        leader.replicate(apply("valFinal")).join();

        group.allowMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> assertThat(getCommitIndex(slowFollower)).isEqualTo(entryCount));

        group.resetAllRulesFrom(leader.getLocalEndpoint());

        eventually(() -> {
            for (RaftNodeImpl raftNode : group.getNodes()) {
                assertThat(getCommitIndex(raftNode)).isEqualTo(entryCount + 1);
                SimpleStateMachine stateMachine = group.getRuntime(raftNode.getLocalEndpoint()).getStateMachine();
                assertThat(stateMachine.size()).isEqualTo(entryCount + 1);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
                assertThat(stateMachine.get(51)).isEqualTo("valFinal");
            }
        });

        assertThat(getSnapshotChunkCollector(slowFollower)).isNull();
    }

    @Test(timeout = 300_000)
    public void when_followerMissesTheLastEntryThatGoesIntoTheSnapshot_then_itCatchesUpWithoutInstallingSnapshot() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).build();
        group = new LocalRaftGroup(3, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();

        RaftNodeImpl[] followers = group.getNodesExcept(leader.getLocalEndpoint());
        RaftNodeImpl slowFollower = followers[1];

        for (int i = 0; i < entryCount - 1; i++) {
            leader.replicate(apply("val" + i)).join();
        }

        eventually(() -> {
            for (RaftNodeImpl follower : group.getNodesExcept(leader.getLocalEndpoint())) {
                assertThat(getMatchIndex(leader, follower.getLocalEndpoint())).isEqualTo(entryCount - 1);
            }
        });

        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);

        leader.replicate(apply("val" + (entryCount - 1))).join();

        eventually(() -> assertThat(getSnapshotEntry(leader).getIndex()).isEqualTo(entryCount));

        leader.replicate(apply("valFinal")).join();

        group.allowMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);

        eventually(() -> {
            for (RaftNodeImpl raftNode : group.getNodes()) {
                assertThat(getCommitIndex(raftNode)).isEqualTo(entryCount + 1);
                SimpleStateMachine stateMachine = group.getRuntime(raftNode.getLocalEndpoint()).getStateMachine();
                assertThat(stateMachine.size()).isEqualTo(entryCount + 1);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
                assertThat(stateMachine.get(51)).isEqualTo("valFinal");
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_followerMissesAFewEntriesBeforeTheSnapshot_then_itCatchesUpWithoutInstallingSnapshot() {
        int entryCount = 50;
        // entryCount * 0.1 - 2
        int missingEntryCountOnSlowFollower = 4;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).build();
        group = new LocalRaftGroup(3, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();

        RaftNodeImpl[] followers = group.getNodesExcept(leader.getLocalEndpoint());
        RaftNodeImpl slowFollower = followers[1];

        for (int i = 0; i < entryCount - missingEntryCountOnSlowFollower; i++) {
            leader.replicate(apply("val" + i)).join();
        }

        eventually(() -> {
            for (RaftNodeImpl follower : group.getNodesExcept(leader.getLocalEndpoint())) {
                assertThat(getMatchIndex(leader, follower.getLocalEndpoint()))
                        .isEqualTo(entryCount - missingEntryCountOnSlowFollower);
            }
        });

        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);

        for (int i = entryCount - missingEntryCountOnSlowFollower; i < entryCount; i++) {
            leader.replicate(apply("val" + i)).join();
        }

        eventually(() -> assertThat(getSnapshotEntry(leader).getIndex()).isEqualTo(entryCount));

        leader.replicate(apply("valFinal")).join();

        group.allowMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);

        eventually(() -> {
            for (RaftNodeImpl raftNode : group.getNodes()) {
                assertThat(getCommitIndex(raftNode)).isEqualTo(entryCount + 1);
                SimpleStateMachine stateMachine = group.getRuntime(raftNode.getLocalEndpoint()).getStateMachine();
                assertThat(stateMachine.size()).isEqualTo(entryCount + 1);
                for (int i = 0; i < entryCount; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
                assertThat(stateMachine.get(51)).isEqualTo("valFinal");
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_isolatedLeaderAppendsEntries_then_itInvalidatesTheirFeaturesUponInstallSnapshot()
            throws Exception {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(5)
                                      .setCommitCountToTakeSnapshot(entryCount).build();
        group = new LocalRaftGroup(3, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        RaftNodeImpl[] followers = group.getNodesExcept(leader.getLocalEndpoint());

        for (int i = 0; i < 40; i++) {
            leader.replicate(apply("val" + i)).join();
        }

        eventually(() -> {
            for (RaftNodeImpl raftNode : group.getNodes()) {
                assertThat(getCommitIndex(raftNode)).isEqualTo(40);
            }
        });

        group.splitMembers(leader.getLocalEndpoint());

        List<Future<Ordered<Object>>> futures = new ArrayList<>();
        for (int i = 40; i < 45; i++) {
            Future<Ordered<Object>> f = leader.replicate(apply("isolated" + i));
            futures.add(f);
        }

        eventually(() -> {
            for (RaftNodeImpl raftNode : followers) {
                assertThat(raftNode.getLeaderEndpoint()).isNotNull().isNotEqualTo(leader.getLocalEndpoint());
            }
        });

        RaftNodeImpl newLeader = group.getNode(followers[0].getLeaderEndpoint());

        for (int i = 40; i < 51; i++) {
            newLeader.replicate(apply("val" + i)).join();
        }

        eventually(() -> {
            for (RaftNodeImpl raftNode : followers) {
                assertThat(getSnapshotEntry(raftNode).getIndex()).isGreaterThan(0);
            }
        });

        group.dropMessagesToMember(leader.getLocalEndpoint(), followers[0].getLocalEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesToMember(leader.getLocalEndpoint(), followers[1].getLocalEndpoint(), AppendEntriesRequest.class);
        group.merge();

        for (Future<Ordered<Object>> f : futures) {
            try {
                f.get();
                fail();
            } catch (ExecutionException e) {
                assertThat(e).hasCauseInstanceOf(IndeterminateStateException.class);
            }
        }

        eventually(() -> {
            for (RaftNodeImpl raftNode : group.getNodes()) {
                assertThat(getCommitIndex(raftNode)).isEqualTo(51);
                SimpleStateMachine stateMachine = group.getRuntime(raftNode.getLocalEndpoint()).getStateMachine();
                assertThat(stateMachine.size()).isEqualTo(51);
                for (int i = 0; i < 51; i++) {
                    assertThat(stateMachine.get(i + 1)).isEqualTo("val" + i);
                }
            }
        });
    }

    @Test(timeout = 300_000)
    public void when_followersLastAppendIsMembershipChange_then_itUpdatesRaftNodeStateWithInstalledSnapshot() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).build();
        group = new LocalRaftGroup(5, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        RaftNodeImpl[] followers = group.getNodesExcept(leader.getLocalEndpoint());

        leader.replicate(apply("val")).join();

        eventually(() -> {
            for (RaftNodeImpl follower : followers) {
                assertThat(getCommitIndex(follower)).isEqualTo(1);
            }
        });

        RaftNodeImpl slowFollower = followers[0];

        for (RaftNodeImpl follower : followers) {
            if (follower != slowFollower) {
                group.dropMessagesToMember(follower.getLocalEndpoint(), follower.getLeaderEndpoint(),
                                           AppendEntriesSuccessResponse.class);
                group.dropMessagesToMember(follower.getLocalEndpoint(), follower.getLeaderEndpoint(),
                                           AppendEntriesFailureResponse.class);
            }
        }

        RaftNodeImpl newRaftNode1 = group.createNewRaftNode();
        CompletableFuture<Ordered<RaftGroupMembers>> f1 = leader.changeMembership(newRaftNode1.getLocalEndpoint(), ADD, 0);

        eventually(() -> {
            for (RaftNodeImpl follower : followers) {
                assertThat(getLastLogOrSnapshotEntry(follower).getIndex()).isEqualTo(2);
            }
        });

        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);

        for (RaftNodeImpl follower : followers) {
            if (follower != slowFollower) {
                group.allowAllMessagesToMember(follower.getLocalEndpoint(), leader.getLeaderEndpoint());
            }
        }

        f1.join();

        for (int i = 0; i < entryCount; i++) {
            leader.replicate(apply("val" + i)).join();
        }

        eventually(() -> assertThat(getSnapshotEntry(leader).getIndex()).isGreaterThanOrEqualTo(entryCount));

        group.allowAllMessagesToMember(leader.getLeaderEndpoint(), slowFollower.getLocalEndpoint());

        eventually(() -> assertThat(getSnapshotEntry(slowFollower).getIndex()).isGreaterThanOrEqualTo(entryCount));

        eventually(() -> {
            assertThat(getCommittedGroupMembers(slowFollower).getLogIndex())
                    .isEqualTo(getCommittedGroupMembers(leader).getLogIndex());
            assertThat(getStatus(slowFollower)).isEqualTo(ACTIVE);
        });
    }

    @Test(timeout = 300_000)
    public void testMembershipChangeBlocksSnapshotBug() {
        // The comments below show how the code behaves before the mentioned bug is fixed.

        int commitIndexAdvanceCount = 50;
        int uncommittedEntryCount = 10;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitIndexAdvanceCount)
                                      .setMaxUncommittedLogEntryCount(uncommittedEntryCount).build();
        group = new LocalRaftGroup(3, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        RaftNodeImpl[] followers = group.getNodesExcept(leader.getLocalEndpoint());

        group.dropMessagesToMember(leader.getLocalEndpoint(), followers[0].getLocalEndpoint(), AppendEntriesRequest.class);

        while (getSnapshotEntry(leader).getIndex() == 0) {
            leader.replicate(apply("into_snapshot")).join();
        }

        // now, the leader has taken a snapshot.
        // It also keeps some already committed entries in the log because followers[0] hasn't appended them.
        // LOG: [ <46 - 49>, <50>], SNAPSHOT INDEX: 50, COMMIT INDEX: 50

        long leaderCommitIndex = getCommitIndex(leader);
        do {
            leader.replicate(apply("committed_after_snapshot")).join();
        } while (getCommitIndex(leader) < leaderCommitIndex + commitIndexAdvanceCount - 1);

        // committing new entries.
        // one more entry is needed to take the next snapshot.
        // LOG: [ <46 - 49>, <50>, <51 - 99 (committed)> ], SNAPSHOT INDEX: 50, COMMIT INDEX: 99

        group.dropMessagesToMember(leader.getLocalEndpoint(), followers[1].getLocalEndpoint(), AppendEntriesRequest.class);

        for (int i = 0; i < uncommittedEntryCount - 1; i++) {
            leader.replicate(apply("uncommitted_after_snapshot"));
        }

        // appended some more entries which will not be committed because the leader has no majority.
        // the last uncommitted index is reserved for membership changed.
        // LOG: [ <46 - 49>, <50>, <51 - 99 (committed)>, <100 - 108 (uncommitted)> ], SNAPSHOT INDEX: 50, COMMIT
        // INDEX: 99
        // There are only 2 empty indices in the log.

        RaftNodeImpl newRaftNode = group.createNewRaftNode();

        Function<RaftMessage, RaftMessage> alterFunc = o -> {
            if (o instanceof AppendEntriesRequest) {
                AppendEntriesRequest request = (AppendEntriesRequest) o;
                List<LogEntry> entries = request.getLogEntries();
                if (entries.size() > 0) {
                    if (entries.get(entries.size() - 1).getOperation() instanceof DefaultUpdateRaftGroupMembersOpOrBuilder) {
                        entries = entries.subList(0, entries.size() - 1);
                        return new DefaultAppendEntriesRequestOrBuilder().setSender(request.getSender())
                                                                         .setTerm(request.getTerm())
                                                                         .setPreviousLogTerm(request.getPreviousLogTerm())
                                                                         .setPreviousLogIndex(request.getPreviousLogIndex())
                                                                         .setCommitIndex(request.getCommitIndex())
                                                                         .setLogEntries(entries)
                                                                         .setQuerySeqNo(request.getQuerySeqNo())
                                                                         .setFlowControlSeqNo(request.getFlowControlSeqNo())
                                                                         .build();
                    } else if (entries.get(0).getOperation() instanceof DefaultUpdateRaftGroupMembersOpOrBuilder) {
                        entries = emptyList();
                        return new DefaultAppendEntriesRequestOrBuilder().setSender(request.getSender())
                                                                         .setTerm(request.getTerm())
                                                                         .setPreviousLogTerm(request.getPreviousLogTerm())
                                                                         .setPreviousLogIndex(request.getPreviousLogIndex())
                                                                         .setCommitIndex(request.getCommitIndex())
                                                                         .setLogEntries(entries)
                                                                         .setQuerySeqNo(request.getQuerySeqNo())
                                                                         .setFlowControlSeqNo(request.getFlowControlSeqNo())
                                                                         .build();
                    }
                }
            }

            return null;
        };

        group.alterMessagesToMember(leader.getLocalEndpoint(), followers[1].getLocalEndpoint(), alterFunc);
        group.alterMessagesToMember(leader.getLocalEndpoint(), newRaftNode.getLocalEndpoint(), alterFunc);

        long lastLogIndex1 = getLastLogOrSnapshotEntry(leader).getIndex();

        leader.changeMembership(newRaftNode.getLocalEndpoint(), ADD, 0);

        // When the membership change entry is appended, the leader's log will be as following:
        // LOG: [ <46 - 49>, <50>, <51 - 99 (committed)>, <100 - 108 (uncommitted)>, <109 (membership change)> ],
        // SNAPSHOT INDEX: 50, COMMIT INDEX: 99

        eventually(() -> assertThat(getLastLogOrSnapshotEntry(leader).getIndex()).isGreaterThan(lastLogIndex1));

        group.allowMessagesToMember(leader.getLocalEndpoint(), followers[1].getLocalEndpoint(), AppendEntriesRequest.class);

        // Then, only the entries before the membership change will be committed because we alter the append request.
        // The log will be:
        // LOG: [ <46 - 49>, <50>, <51 - 108 (committed)>, <109 (membership change)> ], SNAPSHOT INDEX: 50, COMMIT
        // INDEX: 108
        // There is only 1 empty index in the log.

        eventually(() -> {
            assertThat(getCommitIndex(leader)).isEqualTo(lastLogIndex1);
            assertThat(getCommitIndex(followers[1])).isEqualTo(lastLogIndex1);
        });

        //        eventually(() -> {
        //        assertThat(getCommitIndex(leader)).isEqualTo(lastLogIndex1 + 1);
        //        assertThat(getCommitIndex(followers[1])).isEqualTo(lastLogIndex1 + 1);
        //        });

        long lastLogIndex2 = getLastLogOrSnapshotEntry(leader).getIndex();

        leader.replicate(apply("after_membership_change_append"));

        eventually(() -> assertThat(getLastLogOrSnapshotEntry(leader).getIndex()).isGreaterThan(lastLogIndex2));

        // Now the log is full. There is no empty space left.
        // LOG: [ <46 - 49>, <50>, <51 - 108 (committed)>, <109 (membership change)>, <110 (uncommitted)> ], SNAPSHOT
        // INDEX: 50, COMMIT INDEX: 108

        long lastLogIndex3 = getLastLogOrSnapshotEntry(leader).getIndex();

        Future<Ordered<Object>> f = leader.replicate(apply("after_membership_change_append"));

        eventually(() -> assertThat(getLastLogOrSnapshotEntry(leader).getIndex()).isGreaterThan(lastLogIndex3));

        assertThat(f).isNotDone();
    }

    @Test(timeout = 300_000)
    public void when_slowFollowerReceivesAppendRequestThatDoesNotFitIntoItsRaftLog_then_itTruncatesAppendRequestEntries() {
        int appendEntriesRequestBatchSize = 100;
        int commitCountToTakeSnapshot = 100;
        int uncommittedEntryCount = 10;

        RaftConfig config = RaftConfig.newBuilder().setAppendEntriesRequestBatchSize(appendEntriesRequestBatchSize)
                                      .setCommitCountToTakeSnapshot(commitCountToTakeSnapshot)
                                      .setMaxUncommittedLogEntryCount(uncommittedEntryCount).build();
        group = new LocalRaftGroup(5, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        RaftNodeImpl[] followers = group.getNodesExcept(leader.getLocalEndpoint());
        RaftNodeImpl slowFollower1 = followers[0];
        RaftNodeImpl slowFollower2 = followers[1];

        int count = 1;
        for (int i = 0; i < commitCountToTakeSnapshot; i++) {
            leader.replicate(apply("val" + (count++))).join();
        }

        eventually(() -> {
            for (RaftNodeImpl node : group.getNodes()) {
                assertThat(getSnapshotEntry(node).getIndex()).isGreaterThan(0);
            }
        });

        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower1.getLocalEndpoint(), AppendEntriesRequest.class);

        for (int i = 0; i < commitCountToTakeSnapshot - 1; i++) {
            leader.replicate(apply("val" + (count++))).join();
        }

        eventually(() -> assertThat(getCommitIndex(slowFollower2)).isEqualTo(getCommitIndex(leader)));

        // slowFollower2's log: [ <91 - 100 before snapshot>, <100 snapshot>, <101 - 199 committed> ]

        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower2.getLocalEndpoint(), AppendEntriesRequest.class);

        for (int i = 0; i < commitCountToTakeSnapshot / 2; i++) {
            leader.replicate(apply("val" + (count++))).join();
        }

        eventually(() -> assertThat(getSnapshotEntry(leader).getIndex()).isGreaterThan(commitCountToTakeSnapshot));

        // leader's log: [ <191 - 199 before snapshot>, <200 snapshot>, <201 - 249 committed> ]

        group.allowMessagesToMember(leader.getLocalEndpoint(), slowFollower2.getLocalEndpoint(), AppendEntriesRequest.class);

        // leader replicates 50 entries to slowFollower2 but slowFollower2 has only available capacity of 11 indices.
        // so, slowFollower2 appends 11 of these 50 entries in the first AppendRequest, takes a snapshot,
        // and receives another AppendRequest for the remaining entries...

        eventually(() -> {
            assertThat(getCommitIndex(slowFollower2)).isEqualTo(getCommitIndex(leader));
            assertThat(getSnapshotEntry(slowFollower2).getIndex()).isGreaterThan(commitCountToTakeSnapshot);
        });
    }

    @Test(timeout = 300_000)
    public void when_leaderFailsDuringSnapshotTransfer_then_followerTransfersSnapshotFromNewLeader_leaderOnly() {
        when_leaderFailsDuringSnapshotTransfer_then_followerTransfersSnapshotFromNewLeader(false);
    }

    @Test(timeout = 300_000)
    public void when_leaderFailsDuringSnapshotTransfer_then_followerTransfersSnapshotFromNewLeader_leaderAndFollowers() {
        when_leaderFailsDuringSnapshotTransfer_then_followerTransfersSnapshotFromNewLeader(true);
    }

    private void when_leaderFailsDuringSnapshotTransfer_then_followerTransfersSnapshotFromNewLeader(
            boolean transferSnapshotFromFollowersEnabled) {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(1).setLeaderHeartbeatTimeoutSecs(5)
                                      .setCommitCountToTakeSnapshot(entryCount)
                                      .setTransferSnapshotsFromFollowersEnabled(transferSnapshotFromFollowersEnabled).build();
        group = new LocalRaftGroup(3, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();
        RaftNodeImpl[] followers = group.getNodesExcept(leader.getLocalEndpoint());
        RaftNodeImpl slowFollower = followers[0];
        RaftNodeImpl newLeader = followers[1];

        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);

        while (getSnapshotEntry(leader).getIndex() == 0) {
            leader.replicate(apply("val")).join();
        }

        eventually(() -> assertThat(getSnapshotEntry(newLeader).getIndex()).isGreaterThan(0));

        group.dropMessagesToMember(slowFollower.getLocalEndpoint(), leader.getLocalEndpoint(), InstallSnapshotResponse.class);
        group.dropMessagesToMember(slowFollower.getLocalEndpoint(), newLeader.getLocalEndpoint(), InstallSnapshotResponse.class);

        group.allowMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> assertThat(getSnapshotChunkCollector(slowFollower)).isNotNull());

        group.terminateNode(leader.getLocalEndpoint());

        eventually(() -> assertThat(newLeader.getLeaderEndpoint()).isEqualTo(newLeader.getLocalEndpoint()));

        group.allowMessagesToMember(slowFollower.getLocalEndpoint(), newLeader.getLocalEndpoint(), InstallSnapshotResponse.class);

        eventually(() -> assertThat(getSnapshotEntry(slowFollower).getIndex()).isGreaterThan(0));
    }

    @Test(timeout = 300_000)
    public void when_thereAreCrashedFollowers_then_theyAreSkippedDuringSnapshotTransfer() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).build();
        group = new LocalRaftGroup(3, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();

        RaftNodeImpl[] followers = group.getNodesExcept(leader.getLocalEndpoint());
        RaftNodeImpl slowFollower = followers[0];
        RaftNodeImpl otherFollower = followers[1];

        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);
        group.dropMessagesToMember(slowFollower.getLocalEndpoint(), leader.getLocalEndpoint(), InstallSnapshotResponse.class);

        for (int i = 0; i < entryCount; i++) {
            leader.replicate(apply("val" + i)).join();
        }

        eventually(() -> assertThat(getSnapshotEntry(leader).getIndex()).isEqualTo(entryCount));

        leader.replicate(apply("valFinal")).join();

        group.terminateNode(otherFollower.getLocalEndpoint());

        group.allowMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);
        group.allowMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> {
            SnapshotChunkCollector snapshotChunkCollector = getSnapshotChunkCollector(slowFollower);
            assertThat(snapshotChunkCollector).isNotNull();
            assertThat(snapshotChunkCollector.getSnapshottedMembers()).hasSize(1);
        });

        group.allowMessagesToMember(slowFollower.getLocalEndpoint(), leader.getLocalEndpoint(), InstallSnapshotResponse.class);

        eventually(() -> assertThat(getCommitIndex(slowFollower)).isEqualTo(getCommitIndex(leader)));
        assertThat(getSnapshotChunkCollector(slowFollower)).isNull();
    }

    @Test
    public void when_leaderCrashesDuringSnapshotTransfer_then_newLeaderSendsItsSnapshottedMembers() {
        int entryCount = 50;
        RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(entryCount).setLeaderHeartbeatPeriodSecs(1)
                                      .setLeaderHeartbeatTimeoutSecs(5).build();
        group = new LocalRaftGroup(5, config);
        group.start();

        RaftNodeImpl leader = group.waitUntilLeaderElected();

        RaftNodeImpl[] followers = group.getNodesExcept(leader.getLocalEndpoint());
        RaftNodeImpl slowFollower = followers[0];

        for (RaftNodeImpl follower : followers) {
            if (follower != slowFollower) {
                group.dropMessagesToMember(follower.getLocalEndpoint(), slowFollower.getLocalEndpoint(),
                                           InstallSnapshotRequest.class);
                group.dropMessagesToMember(slowFollower.getLocalEndpoint(), follower.getLocalEndpoint(),
                                           InstallSnapshotResponse.class);
            }
        }

        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), AppendEntriesRequest.class);
        group.dropMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);
        group.dropMessagesToMember(slowFollower.getLocalEndpoint(), leader.getLocalEndpoint(), InstallSnapshotResponse.class);

        while (getSnapshotEntry(leader).getIndex() == 0) {
            leader.replicate(apply("val")).join();
        }

        eventually(() -> assertThat(getSnapshotEntry(leader).getIndex()).isGreaterThan(0));

        group.allowMessagesToMember(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> {
            SnapshotChunkCollector snapshotChunkCollector = getSnapshotChunkCollector(slowFollower);
            assertThat(snapshotChunkCollector).isNotNull();
            assertThat(snapshotChunkCollector.getSnapshottedMembers().size()).isEqualTo(1);
        });

        group.terminateNode(leader.getLocalEndpoint());

        for (RaftNodeImpl follower : followers) {
            eventually(() -> assertThat(follower.getLeaderEndpoint()).isNotNull().isNotEqualTo(leader.getLocalEndpoint()));
        }

        RaftNodeImpl newLeader = group.getLeaderNode();
        newLeader.replicate(apply("newLeaderVal")).join();

        group.allowMessagesToMember(newLeader.getLocalEndpoint(), slowFollower.getLocalEndpoint(), InstallSnapshotRequest.class);

        eventually(() -> assertThat(getSnapshotChunkCollector(slowFollower).getSnapshottedMembers().size()).isGreaterThan(1));

        for (RaftNodeImpl follower : followers) {
            if (follower != slowFollower) {
                group.allowMessagesToMember(follower.getLocalEndpoint(), slowFollower.getLocalEndpoint(),
                                            InstallSnapshotRequest.class);
                group.allowMessagesToMember(slowFollower.getLocalEndpoint(), follower.getLocalEndpoint(),
                                            InstallSnapshotResponse.class);
            }
        }

        eventually(() -> assertThat(getCommitIndex(slowFollower)).isEqualTo(getCommitIndex(newLeader)));
        assertThat(getSnapshotChunkCollector(slowFollower)).isNull();
    }

}
