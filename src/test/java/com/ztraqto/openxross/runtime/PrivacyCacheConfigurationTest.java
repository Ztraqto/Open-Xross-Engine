package com.ztraqto.openxross.runtime;

import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.Set;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class PrivacyCacheConfigurationTest {
    @Test void declaresCompleteMemberOverridePrivacyPolicy() {
        assertEquals(CacheFlag.MEMBER_OVERRIDES, PrivacyCacheConfiguration.REQUIRED_CACHE);
        assertEquals(MemberCachePolicy.ALL, PrivacyCacheConfiguration.MEMBER_POLICY);
        assertEquals(ChunkingFilter.ALL, PrivacyCacheConfiguration.CHUNKING);
    }
    @Test void productionBuilderPathAppliesPrivacyPolicy() throws Exception {
        DefaultShardManagerBuilder builder = DefaultShardManagerBuilder.createDefault("a".repeat(60));
        XrossBotRuntime.configurePrivacyCaches(builder);
        Field cacheFlags = DefaultShardManagerBuilder.class.getDeclaredField("cacheFlags"); cacheFlags.setAccessible(true);
        Field memberPolicy = DefaultShardManagerBuilder.class.getDeclaredField("memberCachePolicy"); memberPolicy.setAccessible(true);
        Field chunking = DefaultShardManagerBuilder.class.getDeclaredField("chunkingFilter"); chunking.setAccessible(true);
        assertEquals(true, ((Set<?>) cacheFlags.get(builder)).contains(CacheFlag.MEMBER_OVERRIDES));
        assertEquals(MemberCachePolicy.ALL, memberPolicy.get(builder));
        assertEquals(ChunkingFilter.ALL, chunking.get(builder));
    }
}
