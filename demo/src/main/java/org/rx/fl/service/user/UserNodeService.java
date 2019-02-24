package org.rx.fl.service.user;

import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.ErrorCode;
import org.rx.common.InvalidOperationException;
import org.rx.common.NQuery;
import org.rx.common.SystemException;
import org.rx.fl.repository.UserMapper;
import org.rx.fl.util.DbUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.rx.common.Contract.require;
import static org.rx.common.Contract.values;
import static org.rx.fl.repository.UserMapper.rootId;

/**
 * 分类存储，提供对分类的增删改查等操作的支持。
 * <p>
 * 类别（分类）是用于归类、整理文章资源的数据信息。
 * 每个分类都可以拥有若干子分类，但最多只能属于一个父分类，没有父分类的称为
 * 顶级分类。分类的从属关系可以看做一棵多叉数。
 * <p>
 * 除了相互之间的关系外，分类拥有ID、名称这两个属性。其中ID为int，
 * 由数据库自动生成，你也可以添加其它属性。
 * <p>
 * 分类树有一个根节点，其ID为0，且不可修改ID、移动和删除。
 */
@Service
@Slf4j
public class UserNodeService {
    private UserMapper userMapper;

    @Autowired
    public UserNodeService(UserMapper userMapper) {
        this.userMapper = userMapper;
        try {
            if (containsNode(rootId)) {
                return;
            }
            userMapper.insertNode(rootId);
        } catch (Exception e) {
            log.warn("TreedUserService", e);
        }
    }

    //region nodes
    @ErrorCode(value = "notFound", messageKeys = {"$id"})
    public UserNode getNode(String id) {
        if (!rootId.equals(id) && !Boolean.TRUE.equals(userMapper.contains(id))) {
            throw new SystemException(values(id), "notFound");
        }

        UserNode node = new UserNode();
        node.setId(id);
        node.setPercent(userMapper.selectPercent(id));
        node.setExist(getDegree(node) != null);
        return node;
    }

    private List<UserNode> getNode(String[] ids) {
        return NQuery.of(ids).select(p -> getNode(p)).toList();
    }

    /**
     * 查询指定分类往上第n级分类。
     * getParent: degree=1
     *
     * @param degree 距离
     * @return 上级分类，如果不存在则为null
     */
    public UserNode getAncestor(UserNode user, int degree) {
        require(user);
        DbUtil.checkPositive(degree, "degree");

        String parent = userMapper.selectAncestorId(user.getId(), degree);
        return parent == null ? null : getNode(parent);
    }

    /**
     * 分类的下的第n级子分类。
     * getChildren: degree=1
     *
     * @param degree 向下级数，1表示直属子分类
     * @return 子类列表，如果id所指定的分类不存在、或没有符合条件的分类，则返回空列表
     * @throws IllegalArgumentException 如果id小于0，或n不是正数
     */
    public List<UserNode> getChildren(UserNode user, int degree) {
        require(user);
        DbUtil.checkPositive(degree, "degree");

        return getNode(userMapper.selectSubIds(user.getId(), degree));
    }

    /**
     * 获取由顶级分类到此分类(含)路径上的所有的分类对象。
     * 如果指定的分类不存在，则返回空列表。
     *
     * @return 分类实体列表，越靠上的分类在列表中的位置越靠前
     */
    public List<UserNode> getPath(UserNode user) {
        require(user);

        return getNode(userMapper.selectPathToRootIds(user.getId()));
    }

    /**
     * 获取此分类(含)到其某个的上级分类（不含）之间的所有分类的实体对象（仅查询id和name属性）。
     * 如果指定的分类、上级分类不存在，或是上级分类不是指定分类的上级，则返回空列表
     *
     * @param ancestorId 上级分类的id，若为0则表示获取到一级分类（含）的列表。
     * @return 分类实体列表，越靠上的分类在列表中的位置越靠前。
     * @throws IllegalArgumentException 如果ancestor小于1。
     */
    public List<UserNode> getPathRelativeTo(UserNode user, String ancestorId) {
        require(user, ancestorId);

        return getNode(userMapper.selectPathToAncestorIds(user.getId(), ancestorId));
    }

    /**
     * 查询分类是哪一级的，根分类级别是0。
     *
     * @return 级别
     */
    public Integer getDegree(UserNode user) {
        require(user);

        return userMapper.selectDistance(rootId, user.getId());
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
    public void moveTo(UserNode user, String target) {
        require(user, target);
        checkModifyNode(user.getId(), false);
        if (user.getId().equals(target)) {
            throw new IllegalArgumentException("不能移动到自己下面");
        }
        checkModifyNode(target, true);

        moveSubTree(user.getId(), userMapper.selectAncestorId(user.getId(), 1));
        moveNode(user.getId(), target);
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
    public void moveTreeTo(UserNode user, String target) {
        require(user, target);
        checkModifyNode(user.getId(), false);
        checkModifyNode(target, true);

        /* 移动分移到自己子树下和无关节点下两种情况 */
        Integer distance = userMapper.selectDistance(user.getId(), target);

        // noinspection StatementWithEmptyBody
        if (distance == null) {
            // 移动到父节点或其他无关系节点，不需要做额外动作
        } else if (distance == 0) {
            throw new IllegalArgumentException("不能移动到自己下面");
        } else {
            // 如果移动的目标是其子类，需要先把子类移动到本类的位置
            String parent = userMapper.selectAncestorId(user.getId(), 1);
            moveNode(target, parent);
            moveSubTree(target, target);
        }

        moveNode(user.getId(), target);
        moveSubTree(user.getId(), user.getId());
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
    private void moveSubTree(String id, String parent) {
        String[] subs = userMapper.selectSubIds(id, 1);
        for (String sub : subs) {
            moveNode(sub, parent);
            moveSubTree(sub, sub);
        }
    }
    //#endregion

    public void create(UserNode user) {
        create(user, rootId);
    }

    /**
     * 新增一个分类，其ID属性将自动生成或计算，并返回。
     * 新增分类的继承关系由parent属性指定，parent为0表示该分类为一级分类。
     *
     * @param user     分类实体对象
     * @param parentId 上级分类id
     * @throws IllegalArgumentException 如果parent所指定的分类不存在、category为null或category中存在属性为null
     */
    @Transactional
    public void create(UserNode user, String parentId) {
        require(user);
        checkModifyNode(user.getId(), false);
        if (containsNode(user.getId())) {
            throw new InvalidOperationException("当前分类用户已存在");
        }
        checkModifyNode(parentId, true);

        userMapper.insertPath(user.getId(), parentId);
        userMapper.insertNode(user.getId());
        savePercent(user);
    }

    public void savePercent(UserNode user) {
        require(user);

        userMapper.updatePercent(user.getId(), user.getPercent());
    }

    private void checkModifyNode(String id, boolean isParent) {
        if (!isParent && rootId.equals(id)) {
            throw new UnsupportedOperationException("根分类不支持此操作");
        }
        if (!rootId.equals(id) && !Boolean.TRUE.equals(userMapper.contains(id))) {
            throw new IllegalArgumentException("分类用户不存在");
        }
    }

    private boolean containsNode(String id) {
        return id.equals(userMapper.selectAncestorId(id, 0));
    }

    /**
     * 删除一个分类，原来在该分类下的子分类将被移动到该分类的父分类中，
     * 如果此分类是一级分类，则删除后子分类将全部成为一级分类。
     * <p>
     * 顶级分类不可删除。
     *
     * @param id 要删除的分类的id
     * @throws IllegalArgumentException 如果指定id的分类不存在
     */
    @Transactional
    public void delete(String id) {
        require(id);
        checkModifyNode(id, false);

        String parent = userMapper.selectAncestorId(id, 1);
        if (parent == null) {
            parent = rootId;
        }
        moveSubTree(id, parent);
        userMapper.deletePath(id);
    }

    /**
     * 删除一个分类及其所有的下级分类。
     * <p>
     * 顶级分类不可删除。
     *
     * @param id 要删除的分类的id
     * @throws IllegalArgumentException 如果指定id的分类不存在
     */
    @Transactional
    public void deleteTree(String id) {
        userMapper.deletePath(id);
        for (String des : userMapper.selectDescendantIds(id)) {
            userMapper.deletePath(des);
        }
    }
}
