package org.windy.playershelter.adapter.storage;

import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.ReturnPoint;
import org.windy.playershelter.domain.port.ReturnPointStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ReturnPointStore} 的 JDBC 实现。落 {@code ps_return_point}（迁移 v9），每人一条。
 *
 * <p>与 {@code ps_pending} 不同：<b>这里不做 TTL 过期</b>。落脚点是长期有效的「上次从哪来」，
 * 玩家可能几天后才 {@code /ps leave}；只在成功返还后由调用方删除。
 */
public final class SqlReturnPointStore implements ReturnPointStore {

    private final Database db;
    private final Logger log;

    public SqlReturnPointStore(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    @Override
    public Optional<ReturnPoint> find(PlayerRef player) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT world_name, server_name, x, y, z, yaw, pitch, pending_arrival, created_at "
                             + "FROM ps_return_point WHERE player_uuid = ?")) {
            ps.setString(1, player.uuid().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ReturnPoint(
                            player,
                            rs.getString("world_name"),
                            rs.getString("server_name"),
                            rs.getDouble("x"),
                            rs.getDouble("y"),
                            rs.getDouble("z"),
                            rs.getFloat("yaw"),
                            rs.getFloat("pitch"),
                            rs.getInt("pending_arrival") != 0,
                            Instant.ofEpochMilli(rs.getLong("created_at"))));
                }
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 读取返还落脚点失败", e);
        }
        return Optional.empty();
    }

    @Override
    public void save(ReturnPoint point) {
        String sql = db.isSqlite()
                ? "INSERT INTO ps_return_point(player_uuid,world_name,server_name,x,y,z,yaw,pitch,"
                  + "pending_arrival,created_at) VALUES(?,?,?,?,?,?,?,?,?,?) "
                  + "ON CONFLICT(player_uuid) DO UPDATE SET world_name=excluded.world_name,"
                  + "server_name=excluded.server_name,x=excluded.x,y=excluded.y,z=excluded.z,"
                  + "yaw=excluded.yaw,pitch=excluded.pitch,pending_arrival=excluded.pending_arrival,"
                  + "created_at=excluded.created_at"
                : "INSERT INTO ps_return_point(player_uuid,world_name,server_name,x,y,z,yaw,pitch,"
                  + "pending_arrival,created_at) VALUES(?,?,?,?,?,?,?,?,?,?) "
                  + "ON DUPLICATE KEY UPDATE world_name=VALUES(world_name),server_name=VALUES(server_name),"
                  + "x=VALUES(x),y=VALUES(y),z=VALUES(z),yaw=VALUES(yaw),pitch=VALUES(pitch),"
                  + "pending_arrival=VALUES(pending_arrival),created_at=VALUES(created_at)";
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, point.player().uuid().toString());
            ps.setString(2, point.worldName());
            ps.setString(3, point.serverName() == null ? "" : point.serverName());
            ps.setDouble(4, point.x());
            ps.setDouble(5, point.y());
            ps.setDouble(6, point.z());
            ps.setFloat(7, point.yaw());
            ps.setFloat(8, point.pitch());
            ps.setInt(9, point.pendingArrival() ? 1 : 0);
            ps.setLong(10, point.createdAt().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 保存返还落脚点失败", e);
        }
    }

    @Override
    public void delete(PlayerRef player) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM ps_return_point WHERE player_uuid = ?")) {
            ps.setString(1, player.uuid().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 删除返还落脚点失败", e);
        }
    }
}
