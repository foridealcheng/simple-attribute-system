package com.attribution.model;

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
 * Fluss KV Value - Click 事件列表
 * 
 * 存储在 Fluss KV Store 中的用户点击会话数据
 * Key: user_id
 * Value: FlussClickSession (包含 Click 列表)
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlussClickSession implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Click 事件列表
     * 按时间戳升序排序
     */
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
}
