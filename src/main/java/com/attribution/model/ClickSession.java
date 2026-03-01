package com.attribution.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Click Session - 用户点击会话
 * 
 * 存储在 KV Store 中的用户点击会话数据
 * Key: user_id
 * Value: ClickSession (包含 Click 列表)
 * 
 * 支持多种 KV 后端：
 * - Redis (当前使用)
 * - Apache Fluss (未来支持)
 * 
 * @author SimpleAttributeSystem
 * @version 2.1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClickSession implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Click 事件列表
     * 按时间戳升序排序
     */
    @JsonProperty("clicks")
    @Builder.Default
    private List<ClickEvent> clicks = new ArrayList<>();

    /**
     * 最后更新时间（毫秒）
     */
    private Long lastUpdateTime;

    /**
     * 会话开始时间（毫秒）
     */
    private Long sessionStartTime;

    /**
     * 版本号（用于乐观锁）
     */
    private Long version;

    /**
     * 用户 ID
     */
    private String userId;

    // 注意：TTL 由 Fluss 表级别配置自动管理 (TABLE_LOG_TTL)
    // 不需要在每个记录中存储 ttlTimestamp
    // 表级别配置：'log.ttl.ms' = '86400000' (24 小时)

    /**
     * 添加 Click 事件
     * 
     * @param click 点击事件
     * @param maxClicks 最大存储数（默认 50）
     */
    public void addClick(ClickEvent click, int maxClicks) {
        if (this.clicks == null) {
            this.clicks = new ArrayList<>();
        }

        // 添加新点击
        this.clicks.add(click);

        // 按时间戳排序
        this.clicks.sort(Comparator.comparingLong(ClickEvent::getTimestamp));

        // 如果超出限制，移除最旧的点击
        while (this.clicks.size() > maxClicks) {
            this.clicks.remove(0);
        }

        // 更新最后更新时间
        this.lastUpdateTime = System.currentTimeMillis();

        // 更新版本号
        this.version = this.version != null ? this.version + 1 : 1L;

        // 设置会话开始时间（如果是第一个点击）
        if (this.sessionStartTime == null) {
            this.sessionStartTime = click.getTimestamp();
        }

        // 更新用户 ID
        if (this.userId == null && click.getUserId() != null) {
            this.userId = click.getUserId();
        }

        // 注意：TTL 由 Fluss 表级别自动管理，不需要手动设置
    }

    /**
     * 批量添加 Click 事件
     * 
     * @param newClicks 新的点击事件列表
     * @param maxClicks 最大存储数（默认 50）
     */
    public void addClicks(List<ClickEvent> newClicks, int maxClicks) {
        if (newClicks == null || newClicks.isEmpty()) {
            return;
        }

        if (this.clicks == null) {
            this.clicks = new ArrayList<>();
        }

        // 添加所有新点击
        this.clicks.addAll(newClicks);

        // 按时间戳排序
        this.clicks.sort(Comparator.comparingLong(ClickEvent::getTimestamp));

        // 如果超出限制，移除最旧的点击
        while (this.clicks.size() > maxClicks) {
            this.clicks.remove(0);
        }

        // 更新最后更新时间
        this.lastUpdateTime = System.currentTimeMillis();

        // 更新版本号
        this.version = this.version != null ? this.version + 1 : 1L;

        // 设置会话开始时间（如果是第一个点击）
        if (this.sessionStartTime == null && !this.clicks.isEmpty()) {
            this.sessionStartTime = this.clicks.get(0).getTimestamp();
        }

        // 更新用户 ID
        if (this.userId == null && !newClicks.isEmpty() && newClicks.get(0).getUserId() != null) {
            this.userId = newClicks.get(0).getUserId();
        }
    }

    /**
     * 获取有效点击列表（在归因窗口内）
     * 
     * @param conversionTime 转化时间
     * @param attributionWindowHours 归因窗口（小时）
     * @return 有效点击列表
     */
    public List<ClickEvent> getValidClicks(long conversionTime, int attributionWindowHours) {
        if (this.clicks == null || this.clicks.isEmpty()) {
            return Collections.emptyList();
        }

        long windowMs = attributionWindowHours * 3600000L; // 小时转毫秒

        return this.clicks.stream()
            .filter(click -> {
                // 检查点击时间是否在归因窗口内
                long timeDiff = conversionTime - click.getTimestamp();
                return timeDiff >= 0 && timeDiff <= windowMs;
            })
            .collect(Collectors.toList());
    }

    /**
     * 清理过期点击
     * 
     * @param currentTime 当前时间
     * @param attributionWindowHours 归因窗口（小时）
     * @return 是否发生了清理
     */
    public boolean cleanupExpired(long currentTime, int attributionWindowHours) {
        if (this.clicks == null || this.clicks.isEmpty()) {
            return false;
        }

        long windowMs = attributionWindowHours * 3600000L;
        long cutoffTime = currentTime - windowMs;

        List<ClickEvent> validClicks = this.clicks.stream()
            .filter(click -> click.getTimestamp() >= cutoffTime)
            .collect(Collectors.toList());

        if (validClicks.size() < this.clicks.size()) {
            this.clicks = validClicks;
            this.lastUpdateTime = currentTime;
            this.version = this.version != null ? this.version + 1 : 1L;
            return true;
        }

        return false;
    }

    /**
     * 获取点击数量
     * 
     * @return 点击数
     */
    public int getClickCount() {
        return this.clicks != null ? this.clicks.size() : 0;
    }

    /**
     * 获取最新的点击
     * 
     * @return 最新的 ClickEvent，如果没有则返回 null
     */
    public ClickEvent getLatestClick() {
        if (this.clicks == null || this.clicks.isEmpty()) {
            return null;
        }
        return this.clicks.get(this.clicks.size() - 1);
    }

    /**
     * 获取最早的点击
     * 
     * @return 最早的 ClickEvent，如果没有则返回 null
     */
    public ClickEvent getEarliestClick() {
        if (this.clicks == null || this.clicks.isEmpty()) {
            return null;
        }
        return this.clicks.get(0);
    }

    // 注意：TTL 相关方法已移除
    // Fluss 表级别自动管理 TTL，不需要应用层处理
}
