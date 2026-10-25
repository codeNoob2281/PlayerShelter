package org.windy.playershelter.api.terrain;

/**
 * 一次地形生成请求（apiVersion 7）。
 *
 * <p>主插件把「建这个庇护所世界」的意图表达成不可变的请求对象交给
 * {@link TerrainProvider}；provider 自行决定用原版、Iris、还是别的生成器。
 *
 * @param worldName 世界名（{@code shelter_<uuid>}），provider 必须用这个名字建世界
 * @param seed      该庇护所固化的种子（随世界冻结，重建时不变）
 * @param fresh     世界存档是否尚不存在（{@code true} = 首建，{@code false} = 重载已有存档）
 * @param audience  触发玩家 UUID（在线则可对其显示生成进度）；可能为 {@code null}
 * @param platform  主插件当前平台标识，便于 provider 判断自身是否适用（如 {@code paper}）
 * @param genType   玩家选择的地形类型（{@code natural} / {@code flat} / {@code void}），小写。
 *                  主插件目前只在 {@code natural} 时派发给 provider，但仍如实告知，便于扩展自行判断。
 */
public record TerrainRequest(String worldName, long seed, boolean fresh, java.util.UUID audience,
                             String platform, String genType) {
}
