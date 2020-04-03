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

package io.microraft.impl.model;

import io.microraft.impl.model.groupop.DefaultTerminateRaftGroupOpOrBuilder;
import io.microraft.impl.model.groupop.DefaultUpdateRaftGroupMembersOpOrBuilder;
import io.microraft.impl.model.log.DefaultLogEntryOrBuilder;
import io.microraft.impl.model.log.DefaultSnapshotChunkOrBuilder;
import io.microraft.impl.model.log.DefaultSnapshotEntry.DefaultSnapshotEntryBuilder;
import io.microraft.impl.model.message.DefaultAppendEntriesFailureResponseOrBuilder;
import io.microraft.impl.model.message.DefaultAppendEntriesRequestOrBuilder;
import io.microraft.impl.model.message.DefaultAppendEntriesSuccessResponseOrBuilder;
import io.microraft.impl.model.message.DefaultInstallSnapshotRequestOrBuilder;
import io.microraft.impl.model.message.DefaultInstallSnapshotResponseOrBuilder;
import io.microraft.impl.model.message.DefaultPreVoteRequestOrBuilder;
import io.microraft.impl.model.message.DefaultPreVoteResponseOrBuilder;
import io.microraft.impl.model.message.DefaultTriggerLeaderElectionRequestOrBuilder;
import io.microraft.impl.model.message.DefaultVoteRequestOrBuilder;
import io.microraft.impl.model.message.DefaultVoteResponseOrBuilder;
import io.microraft.model.RaftModel;
import io.microraft.model.RaftModelFactory;
import io.microraft.model.groupop.TerminateRaftGroupOp.TerminateRaftGroupOpBuilder;
import io.microraft.model.groupop.UpdateRaftGroupMembersOp.UpdateRaftGroupMembersOpBuilder;
import io.microraft.model.log.LogEntry.LogEntryBuilder;
import io.microraft.model.log.SnapshotChunk.SnapshotChunkBuilder;
import io.microraft.model.log.SnapshotEntry.SnapshotEntryBuilder;
import io.microraft.model.message.AppendEntriesFailureResponse.AppendEntriesFailureResponseBuilder;
import io.microraft.model.message.AppendEntriesRequest.AppendEntriesRequestBuilder;
import io.microraft.model.message.AppendEntriesSuccessResponse.AppendEntriesSuccessResponseBuilder;
import io.microraft.model.message.InstallSnapshotRequest.InstallSnapshotRequestBuilder;
import io.microraft.model.message.InstallSnapshotResponse.InstallSnapshotResponseBuilder;
import io.microraft.model.message.PreVoteRequest.PreVoteRequestBuilder;
import io.microraft.model.message.PreVoteResponse.PreVoteResponseBuilder;
import io.microraft.model.message.TriggerLeaderElectionRequest.TriggerLeaderElectionRequestBuilder;
import io.microraft.model.message.VoteRequest.VoteRequestBuilder;
import io.microraft.model.message.VoteResponse.VoteResponseBuilder;

import javax.annotation.Nonnull;

/**
 * Used for creating {@link RaftModel} POJOs.
 * <p>
 * Could be used for testing purposes.
 *
 * @author metanet
 */
public final class DefaultRaftModelFactory
        implements RaftModelFactory {

    public static final RaftModelFactory INSTANCE = new DefaultRaftModelFactory();

    private DefaultRaftModelFactory() {
    }

    @Nonnull
    @Override
    public LogEntryBuilder createLogEntryBuilder() {
        return new DefaultLogEntryOrBuilder();
    }

    @Nonnull
    @Override
    public SnapshotEntryBuilder createSnapshotEntryBuilder() {
        return new DefaultSnapshotEntryBuilder();
    }

    @Nonnull
    @Override
    public SnapshotChunkBuilder createSnapshotChunkBuilder() {
        return new DefaultSnapshotChunkOrBuilder();
    }

    @Nonnull
    @Override
    public AppendEntriesRequestBuilder createAppendEntriesRequestBuilder() {
        return new DefaultAppendEntriesRequestOrBuilder();
    }

    @Nonnull
    @Override
    public AppendEntriesSuccessResponseBuilder createAppendEntriesSuccessResponseBuilder() {
        return new DefaultAppendEntriesSuccessResponseOrBuilder();
    }

    @Nonnull
    @Override
    public AppendEntriesFailureResponseBuilder createAppendEntriesFailureResponseBuilder() {
        return new DefaultAppendEntriesFailureResponseOrBuilder();
    }

    @Nonnull
    @Override
    public InstallSnapshotRequestBuilder createInstallSnapshotRequestBuilder() {
        return new DefaultInstallSnapshotRequestOrBuilder();
    }

    @Nonnull
    @Override
    public InstallSnapshotResponseBuilder createInstallSnapshotResponseBuilder() {
        return new DefaultInstallSnapshotResponseOrBuilder();
    }

    @Nonnull
    @Override
    public PreVoteRequestBuilder createPreVoteRequestBuilder() {
        return new DefaultPreVoteRequestOrBuilder();
    }

    @Nonnull
    @Override
    public PreVoteResponseBuilder createPreVoteResponseBuilder() {
        return new DefaultPreVoteResponseOrBuilder();
    }

    @Nonnull
    @Override
    public TriggerLeaderElectionRequestBuilder createTriggerLeaderElectionRequestBuilder() {
        return new DefaultTriggerLeaderElectionRequestOrBuilder();
    }

    @Nonnull
    @Override
    public VoteRequestBuilder createVoteRequestBuilder() {
        return new DefaultVoteRequestOrBuilder();
    }

    @Nonnull
    @Override
    public VoteResponseBuilder createVoteResponseBuilder() {
        return new DefaultVoteResponseOrBuilder();
    }

    @Nonnull
    @Override
    public TerminateRaftGroupOpBuilder createTerminateRaftGroupOpBuilder() {
        return new DefaultTerminateRaftGroupOpOrBuilder();
    }

    @Nonnull
    @Override
    public UpdateRaftGroupMembersOpBuilder createUpdateRaftGroupMembersOpBuilder() {
        return new DefaultUpdateRaftGroupMembersOpOrBuilder();
    }

}
