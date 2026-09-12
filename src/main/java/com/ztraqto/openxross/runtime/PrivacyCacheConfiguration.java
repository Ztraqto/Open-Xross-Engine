package com.ztraqto.openxross.runtime;

import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

/** Privacy-critical cache policy for administrator-only channel proofing. */
final class PrivacyCacheConfiguration {
    static final CacheFlag REQUIRED_CACHE = CacheFlag.MEMBER_OVERRIDES;
    static final MemberCachePolicy MEMBER_POLICY = MemberCachePolicy.ALL;
    static final ChunkingFilter CHUNKING = ChunkingFilter.ALL;
    private PrivacyCacheConfiguration() { }
    static void apply(DefaultShardManagerBuilder builder) {
        builder.enableCache(REQUIRED_CACHE);
        builder.setMemberCachePolicy(MEMBER_POLICY);
        builder.setChunkingFilter(CHUNKING);
    }
}
