package org.windy.playershelter.domain.port;

import org.windy.playershelter.domain.model.ItemCost;
import org.windy.playershelter.domain.model.PlayerRef;

import java.util.Collections;
import java.util.List;

/**
 * Platform-side inventory charging for upgrade item costs.
 */
public interface ItemCostPort {

    boolean has(PlayerRef player, List<ItemCost> costs);

    boolean withdraw(PlayerRef player, List<ItemCost> costs);

    /**
     * 返回玩家背包中不足的材料列表（每项 amount 为差额）。
     * 若平台不支持查询或所有材料充足，返回空列表。
     */
    default List<ItemCost> missingItems(PlayerRef player, List<ItemCost> costs) {
        return Collections.emptyList();
    }

    ItemCostPort DISABLED = new ItemCostPort() {
        @Override
        public boolean has(PlayerRef player, List<ItemCost> costs) {
            return true;
        }

        @Override
        public boolean withdraw(PlayerRef player, List<ItemCost> costs) {
            return true;
        }

        @Override
        public List<ItemCost> missingItems(PlayerRef player, List<ItemCost> costs) {
            return Collections.emptyList();
        }
    };
}
