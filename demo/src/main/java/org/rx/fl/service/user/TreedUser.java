package org.rx.fl.service.user;

import com.alibaba.fastjson.JSON;
import org.rx.common.NQuery;
import org.rx.common.UserConfig;
import org.rx.fl.repository.UserMapper;
import org.rx.fl.repository.model.User;
import org.rx.fl.util.Utils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

import static org.rx.common.Contract.require;

/**
 * 分类对象，该类使用充血模型，除了属性之外还包含了一些方法。
 */
@Component
public class TreedUser extends User {
    public static TreedUser convert(User user) {
        return ((JSON) JSON.toJSON(user)).toJavaObject(TreedUser.class);
    }

    @Resource
    private UserMapper userMapper;
    @Resource
    private UserConfig userConfig;

    private List<TreedUser> convert(String[] ids) {
        return NQuery.of(ids).select(p -> convert(userMapper.selectByPrimaryKey(p))).toList();
    }

    /**
     * 获取分类的父分类。
     *
     * @return 父分类实体对象，如果指定的分类是一级分类，则返回null
     */
    public TreedUser getParent() {
        return getAncestor(1);
    }

    /**
     * 查询指定分类往上第n级分类。
     *
     * @param level 距离
     * @return 上级分类，如果不存在则为null
     */
    public TreedUser getAncestor(int level) {
        Utils.checkPositive(level, "level");

        String parent = userMapper.selectAncestor(getId(), level);
        return parent == null ? null : convert(userMapper.selectByPrimaryKey(parent));
    }

    /**
     * 分类的下的直属子分类。
     *
     * @return 直属子类列表，如果id所指定的分类不存在、或没有符合条件的分类，则返回空列表
     * @throws IllegalArgumentException 如果id小于0
     */
    public List<TreedUser> getChildren() {
        return getChildren(1);
    }

    /**
     * 分类的下的第n级子分类。
     *
     * @param level 向下级数，1表示直属子分类
     * @return 子类列表，如果id所指定的分类不存在、或没有符合条件的分类，则返回空列表
     * @throws IllegalArgumentException 如果id小于0，或n不是正数
     */
    public List<TreedUser> getChildren(int level) {
        Utils.checkPositive(level, "level");

        return convert(userMapper.selectSubLayerIds(getId(), level));
    }

    /**
     * 获取由顶级分类到此分类(含)路径上的所有的分类对象。
     * 如果指定的分类不存在，则返回空列表。
     *
     * @return 分类实体列表，越靠上的分类在列表中的位置越靠前
     */
    public List<TreedUser> getPath() {
        return convert(userMapper.selectPathToRootIds(getId()));
    }

    /**
     * 获取此分类(含)到其某个的上级分类（不含）之间的所有分类的实体对象（仅查询id和name属性）。
     * 如果指定的分类、上级分类不存在，或是上级分类不是指定分类的上级，则返回空列表
     *
     * @param ancestorId 上级分类的id，若为0则表示获取到一级分类（含）的列表。
     * @return 分类实体列表，越靠上的分类在列表中的位置越靠前。
     * @throws IllegalArgumentException 如果ancestor小于1。
     */
    public List<TreedUser> getPathRelativeTo(String ancestorId) {
        require(ancestorId);

        return convert(userMapper.selectPathToAncestorIds(getId(), ancestorId));
    }

    /**
     * 查询分类是哪一级的，根分类级别是0。
     *
     * @return 级别
     */
    public int getLevel() {
        return userMapper.selectDistance(UserMapper.emptyId, getId());
    }

    /**
     * 将一个分类移动到目标分类下面（成为其子分类）。被移动分类的子类将自动上浮（成为指定分类
     * 父类的子分类），即使目标是指定分类原本的父类。
     * <p>
     * 例如下图(省略顶级分类)：
     * 1                                    1
     * |                                  / | \
     * 2                                 3  4  5
     * / | \         (id=2).moveTo(7)           / \
     * 3  4  5       ----------------->         6   7
     * / \                                /  / | \
     * 6    7                              8  9  10 2
     * /    /  \
     * 8    9    10
     *
     * @param target 目标分类的id
     * @throws IllegalArgumentException 如果target所表示的分类不存在、或此分类的id==target
     */
    @Transactional
    public void moveTo(String target) {
        require(target);
        if (userConfig.getRootId().equals(target)) {
            throw new UnsupportedOperationException("根分类不支持此操作");
        }
        if (getId().equals(target)) {
            throw new IllegalArgumentException("不能移动到自己下面");
        }
        if (!UserMapper.emptyId.equals(target) && userMapper.contains(target) == null) {
            throw new IllegalArgumentException("指定的上级分类不存在");
        }

        moveSubTree(getId(), userMapper.selectAncestor(getId(), 1));
        moveNode(getId(), target);
    }

    /**
     * 将一个分类移动到目标分类下面（成为其子分类），被移动分类的子分类也会随着移动。
     * 如果目标分类是被移动分类的子类，则先将目标分类（连带子类）移动到被移动分类原来的
     * 的位置，再移动需要被移动的分类。
     * <p>
     * 例如下图(省略顶级分类)：
     * 1                                      1
     * |                                      |
     * 2                                      7
     * / | \        (id=2).moveTreeTo(7)      / | \
     * 3  4  5      -------------------->     9  10  2
     * / \                                  / | \
     * 6    7                                3  4  5
     * /    /  \                                    |
     * 8    9    10                                  6
     * |
     * 8
     *
     * @param target 目标分类的id
     * @throws IllegalArgumentException 如果id或target所表示的分类不存在、或id==target
     */
    @Transactional
    public void moveTreeTo(String target) {
        require(target);
        if (userConfig.getRootId().equals(target)) {
            throw new UnsupportedOperationException("根分类不支持此操作");
        }
        if (!UserMapper.emptyId.equals(target) && userMapper.contains(target) == null) {
            throw new IllegalArgumentException("指定的上级分类不存在");
        }

        /* 移动分移到自己子树下和无关节点下两种情况 */
        Integer distance = userMapper.selectDistance(getId(), target);

        // noinspection StatementWithEmptyBody
        if (distance == null) {
            // 移动到父节点或其他无关系节点，不需要做额外动作
        } else if (distance == 0) {
            throw new IllegalArgumentException("不能移动到自己下面");
        } else {
            // 如果移动的目标是其子类，需要先把子类移动到本类的位置
            String parent = userMapper.selectAncestor(getId(), 1);
            moveNode(target, parent);
            moveSubTree(target, target);
        }

        moveNode(getId(), target);
        moveSubTree(getId(), getId());
    }

    /**
     * 将指定节点移动到另某节点下面，该方法不修改子节点的相关记录，
     * 为了保证数据的完整性，需要与moveSubTree()方法配合使用。
     *
     * @param id     指定节点id
     * @param parent 某节点id
     */
    private void moveNode(String id, String parent) {
        userMapper.deletePath(id);
        userMapper.insertPath(id, parent);
        userMapper.insertNode(id);
    }

    /**
     * 将指定节点的所有子树移动到某节点下
     * 如果两个参数相同，则相当于重建子树，用于父节点移动后更新路径
     *
     * @param id     指定节点id
     * @param parent 某节点id
     */
    void moveSubTree(String id, String parent) {
        String[] subs = userMapper.selectTopSubIds(id);
        for (String sub : subs) {
            moveNode(sub, parent);
            moveSubTree(sub, sub);
        }
    }
}
