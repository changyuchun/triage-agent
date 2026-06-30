package com.cyc.cyctest.agent.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 会话持久化实体（MySQL 主存储）。
 * turns_json 归档后清空；summary 永远保留。
 */
@Entity
@Table(name = "agent_session")
public class AgentSessionEntity {

    @Id
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "title", length = 100)
    private String title;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "slot_state_json", columnDefinition = "TEXT")
    private String slotStateJson;

    @Column(name = "turns_json", columnDefinition = "MEDIUMTEXT")
    private String turnsJson;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected AgentSessionEntity() {}

    public AgentSessionEntity(String sessionId, String userId, String title,
                              String summary, String slotStateJson, String turnsJson,
                              Instant createdAt, Instant lastActiveAt) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.title = title;
        this.summary = summary;
        this.slotStateJson = slotStateJson;
        this.turnsJson = turnsJson;
        this.createdAt = createdAt;
        this.lastActiveAt = lastActiveAt;
    }

    public String getSessionId()    { return sessionId; }
    public String getUserId()       { return userId; }
    public String getTitle()        { return title; }
    public String getSummary()      { return summary; }
    public String getSlotStateJson(){ return slotStateJson; }
    public String getTurnsJson()    { return turnsJson; }
    public Instant getCreatedAt()   { return createdAt; }
    public Instant getLastActiveAt(){ return lastActiveAt; }
    public Instant getArchivedAt()  { return archivedAt; }

    public void setTitle(String title)            { this.title = title; }
    public void setSummary(String summary)        { this.summary = summary; }
    public void setSlotStateJson(String v)        { this.slotStateJson = v; }
    public void setTurnsJson(String v)            { this.turnsJson = v; }
    public void setLastActiveAt(Instant v)        { this.lastActiveAt = v; }
    public void setArchivedAt(Instant v)          { this.archivedAt = v; }
}
