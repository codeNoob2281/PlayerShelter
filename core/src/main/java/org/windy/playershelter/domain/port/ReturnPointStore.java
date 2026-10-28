package org.windy.playershelter.domain.port;

import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.ReturnPoint;

import java.util.Optional;

/**
 * 离开家园前的落脚点（{@code /ps leave} 的返还目标）持久化端口。
 *
 * <p>与 {@link PendingActionStore}（跨服「进庇护所」待办）对称：这里管的是「出庇护所」要回到哪。
 * 跨服场景下各后端共享 MySQL，返还目标可能落在别的后端服，故记录里带 serverName，
 * 落地前用 {@link ReturnPoint#pendingArrival()} 标记「已发车」。
 */
public interface ReturnPointStore {

    /** 取玩家记录的落脚点；无则空。 */
    Optional<ReturnPoint> find(PlayerRef player);

    /** 落库（upsert，每人一条）。 */
    void save(ReturnPoint point);

    /** 删除记录（返还成功后调）。 */
    void delete(PlayerRef player);

    /** 内存实现（单服/测试；跨服无意义但不报错）。 */
    final class InMemory implements ReturnPointStore {
        private final java.util.Map<PlayerRef, ReturnPoint> map = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public Optional<ReturnPoint> find(PlayerRef player) {
            return Optional.ofNullable(map.get(player));
        }

        @Override public void save(ReturnPoint point) {
            map.put(point.player(), point);
        }

        @Override public void delete(PlayerRef player) {
            map.remove(player);
        }
    }
}
