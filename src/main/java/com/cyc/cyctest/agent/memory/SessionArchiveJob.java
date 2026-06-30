package com.cyc.cyctest.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话生命周期管理定时任务。
 * <p>
 * 两个阶段：
 * 1. 归档（180天）：forceCompressAll → 最终摘要覆盖全部对话 → 保存回 Redis（turns清空，summary完整）
 * 2. 删除（365天）：从 Redis 和 ZSET 物理删除，不可恢复
 * <p>
 * 先删除再归档，避免对 365+ 天的 session 做无意义压缩。
 */
@Component
public class SessionArchiveJob {

    private static final Logger log = LoggerFactory.getLogger(SessionArchiveJob.class);

    private static final int ARCHIVE_DAYS = 180;
    private static final int DELETE_DAYS  = 365;

    private final SessionRepository sessionRepo;
    private final MemoryCompressionService compressionService;

    public SessionArchiveJob(SessionRepository sessionRepo,
                             MemoryCompressionService compressionService) {
        this.sessionRepo = sessionRepo;
        this.compressionService = compressionService;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void run() {
        deleteExpired();
        archiveInactive();
    }

    private void deleteExpired() {
        List<String> toDelete = sessionRepo.findOldSessionIds(DELETE_DAYS);
        if (toDelete.isEmpty()) return;
        for (String id : toDelete) {
            try {
                sessionRepo.deleteSession(id);
            } catch (Exception e) {
                log.warn("删除会话 {} 失败: {}", id, e.getMessage());
            }
        }
        log.info("物理删除 {} 个过期会话（{}天以上）", toDelete.size(), DELETE_DAYS);
    }

    private void archiveInactive() {
        // findOldSessionIds(180) 返回 180+ 天未活跃的；365+ 天的已被 deleteExpired 清掉
        List<String> toArchive = sessionRepo.findOldSessionIds(ARCHIVE_DAYS);
        if (toArchive.isEmpty()) return;
        int success = 0;
        for (String id : toArchive) {
            try {
                ConversationContext ctx = sessionRepo.loadOrCreate(id);
                compressionService.forceCompressAll(ctx); // turns → summary，turns 清空至保留3条
                sessionRepo.save(ctx);                    // 压缩后保存，Redis value 大幅缩小
                success++;
            } catch (Exception e) {
                log.warn("归档会话 {} 失败，跳过: {}", id, e.getMessage());
            }
        }
        log.info("归档完成：{}/{} 个会话（{}天以上未活跃）", success, toArchive.size(), ARCHIVE_DAYS);
    }
}
