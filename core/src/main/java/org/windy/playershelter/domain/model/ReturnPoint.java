package org.windy.playershelter.domain.model;

import java.time.Instant;

/**
 * 「离开家园前的落脚点」——{@code /ps leave} 的返还目标。
 *
 * <p>玩家被送进庇护所世界（{@code /ps home}、{@code /ps visit}、{@code /ps admin tp} 等）之前，
 * 记下他当时所在的位置（世界 + 服务器 + 坐标朝向）；执行 {@code /ps leave} 时原样送回去，
 * 方便从主世界/下界/末地进家园后再回到原处。
 *
 * <p>两条刻意的设计：
 * <ul>
 *   <li><b>只在「从非庇护所世界进入」时记录</b>：已经在某个庇护所世界里时（串门链）不覆盖，
 *       否则 A 家 → B 家 → 离开 会被送回 A 家而不是最初的主世界。</li>
 *   <li><b>带 serverName</b>：跨服模式下庇护所可能在别的后端服，落地位置也在原后端服，
 *       所以返还可能需要再换一次服（{@link #pendingArrival()} 标记「已发车、到站后消费」）。</li>
 * </ul>
 *
 * @param player         玩家
 * @param worldName      原世界名（{@code world} / {@code world_nether} / {@code world_the_end} …）
 * @param serverName     原世界所在后端服名（单服为空串）
 * @param x              坐标
 * @param y              坐标
 * @param z              坐标
 * @param yaw            水平朝向
 * @param pitch          俯仰
 * @param pendingArrival 已通过代理发车，到站后由目标后端消费这条待返记录
 * @param createdAt      记录时刻（诊断用）
 */
public record ReturnPoint(PlayerRef player, String worldName, String serverName,
                          double x, double y, double z, float yaw, float pitch,
                          boolean pendingArrival, Instant createdAt) {

    /** 新建一条「未发车」的落脚点记录。 */
    public static ReturnPoint of(PlayerRef player, String worldName, String serverName,
                                 double x, double y, double z, float yaw, float pitch) {
        return new ReturnPoint(player, worldName, serverName, x, y, z, yaw, pitch, false, Instant.now());
    }

    public ReturnPoint withPendingArrival(boolean pending) {
        return new ReturnPoint(player, worldName, serverName, x, y, z, yaw, pitch, pending, createdAt);
    }
}
