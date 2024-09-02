package org.rx.jdbc;

import lombok.Data;
import org.junit.jupiter.api.Test;
import org.rx.bean.DateTime;
import org.rx.io.EntityQueryLambda;

import java.util.Date;

public class TestJdbc {
    @Data
    public static class PoUser {
        Long id;
        String userName;
        String pwd;
        Integer age;
        Date createAt;
        Date modifyAt;
    }

    @Test
    public void jdbcExec() {
//        JdbcExecutor d = new JdbcExecutor("jdbc:mysql://", "", "bG1hbG1#");
//        JdbcUtil.print(d.executeQuery("select * from emr.t_third_api_record\n" +
//                "# where third_order_id = 'A01202402201715030375693'\n" +
//                "order by updated_time desc"));

        PoUser po = new PoUser();
        po.setId(1L);
        po.setUserName("rocky");
        po.setAge(16);
        po.setCreateAt(DateTime.now());
        po.setModifyAt(new Date());
        System.out.println(JdbcUtil.buildInsertSql(po, t -> t.getSimpleName().toUpperCase(), c -> {
            switch (c) {
                case "id":
                    return "_id";
            }
            return c;
        }, (c, v) -> {
            if (v instanceof Date) {
                return "2024-01-01";
            }
            return v;
        }));
        System.out.println(JdbcUtil.buildUpdateSql(po, new EntityQueryLambda<>(PoUser.class).eq(PoUser::getId, 1024).eq(PoUser::getUserName, "wyf")));
    }
}
