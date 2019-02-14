package org.rx.fl.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.rx.fl.repository.model.User;
import org.rx.fl.repository.model.UserExample;

/**
 * UserMapper继承基类
 * 基于ClosureTable的数结构存储
 */
@Mapper
public interface UserMapper extends MyBatisBaseDao<User, String, UserExample> {
    String emptyId = "00000000-0000-0000-0000-000000000000";

    /**
     * 查询某一层的节点的数量。
     *
     * @param level 层级
     * @return 节点数量
     */
    @Select("SELECT COUNT(*) FROM t_user_tree WHERE ancestor='" + emptyId + "' AND distance=#{level}")
    long selectCountByLayer(int level);

    /**
     * 查询某个节点的子树中所有的节点的id，不包括参数所指定的节点
     *
     * @param ancestorId 节点id
     * @return 子节点id
     */
    @Select("SELECT descendant FROM t_user_tree WHERE ancestor=#{ancestorId} AND distance>0")
    String[] selectDescendantIds(String ancestorId);

    /**
     * 查询某个节点的第n级子节点
     *
     * @param ancestorId 祖先节点ID
     * @param level      距离（0表示自己，1表示直属子节点）
     * @return 子节点列表
     */
    @Select("SELECT descendant FROM t_user_tree WHERE ancestor=#{ancestorId} AND distance=#{level}")
    String[] selectSubLayerIds(String ancestorId, int level);

    /**
     * 查询某个节点的第N级父节点。如果id指定的节点不存在、操作错误或是数据库被外部修改，
     * 则可能查询不到父节点，此时返回null。
     *
     * @param descendantId 节点id
     * @param level        祖先距离（0表示自己，1表示直属父节点）
     * @return 父节点id，如果不存在则返回null
     */
    @Select("SELECT ancestor FROM t_user_tree WHERE descendant=#{descendantId} AND distance=#{level}")
    String selectAncestor(String descendantId, int level);

    /**
     * 查询由id指定节点(含)到根节点(不含)的路径
     * 比下面的<code>selectPathToAncestor</code>简单些。
     *
     * @param descendantId 节点ID
     * @return 路径列表。如果节点不存在，则返回空列表
     */
    @Select("SELECT ancestor FROM t_user_tree WHERE descendant=#{descendantId} AND ancestor>0 ORDER BY distance DESC")
    String[] selectPathToRootIds(String descendantId);

    /**
     * 查询由id指定节点(含)到指定上级节点(不含)的路径
     *
     * @param descendantId 节点ID
     * @param ancestorId   上级节点的ID
     * @return 路径列表。如果节点不存在，或上级节点不存在，则返回空列表
     */
    @Select("SELECT ancestor FROM t_user_tree " +
            "WHERE descendant=#{descendantId} AND " +
            "distance<(SELECT distance FROM t_user_tree WHERE descendant=#{descendantId} AND ancestor=#{ancestorId}) " +
            "ORDER BY distance DESC")
    String[] selectPathToAncestorIds(String descendantId, String ancestorId);

    /**
     * 查找某节点下的所有直属子节点的id
     * 该方法与上面的<code>selectSubLayer</code>不同，它只查询节点的id，效率高点
     *
     * @param parentId 分类id
     * @return 子类id数组
     */
    @Select("SELECT descendant FROM t_user_tree WHERE ancestor=#{parentId} AND distance=1")
    String[] selectTopSubIds(String parentId);

    /**
     * 查询某节点到它某个祖先节点的距离
     *
     * @param ancestorId   父节点id
     * @param descendantId 节点id
     * @return 距离（0表示到自己的距离）,如果ancestor并不是其祖先节点则返回null
     */
    @Select("SELECT distance FROM t_user_tree WHERE descendant=#{descendantId} AND ancestor=#{ancestorId}")
    Integer selectDistance(String ancestorId, String descendantId);

    /**
     * 复制父节点的路径结构,并修改descendant和distance
     *
     * @param id       节点id
     * @param parentId 父节点id
     */
    @Insert("INSERT INTO t_user_tree(ancestor,descendant,distance) " +
            "(SELECT ancestor,#{id},distance+1 FROM t_user_tree WHERE descendant=#{parentId})")
    void insertPath(String id, String parentId);

    /**
     * 在关系表中插入对自身的连接
     *
     * @param id 节点id
     */
    @Insert("INSERT INTO t_user_tree(ancestor,descendant,distance) VALUES(#{id},#{id},0)")
    void insertNode(String id);

    /**
     * 从树中删除某节点的路径。注意指定的节点可能存在子树，而子树的节点在该节点之上的路径并没有改变，
     * 所以使用该方法后还必须手动修改子节点的路径以确保树的正确性
     *
     * @param id 节点id
     */
    @Delete("DELETE FROM t_user_tree WHERE descendant=#{id}")
    void deletePath(String id);

    /**
     * 判断分类是否存在
     *
     * @param id 分类id
     * @return true表示存在，null或false表示不存在
     */
    @Select("SELECT 1 FROM t_user WHERE id=#{id}")
    Boolean contains(String id);
}
