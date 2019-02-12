select * from t_user where wx_open_id = 'yan_131415';

#查找用户商品
select * from  t_user_goods t
inner join t_user t1 on t.user_id = t1.id
where t1.wx_open_id = 'yan_131415'
order by t.create_time desc;

#匹配用户商品
select t.*,t1.* from t_order t
inner join t_user_goods t1 on t.goods_id = t1.goods_id
inner join t_user t2 on t1.user_id = t2.id
where t2.wx_open_id = 'yan_131415'
order by t.create_time desc;

#检查商品是否多用户
select * from t_user_goods where goods_id = '7437788';

select * from t_user_goods t1
inner join t_order t2 on t1.goods_id = t2.goods_id
where 1=1
and t1.user_id = 'c7bc68c9-d6f0-da3b-e3a7-7db62fd0d567'
order by t2.create_time desc
and create_time >= '2019-01-13' and goods_id = '580553299436';

select * from t_user t1
inner join t_balance_log t2 on t1.id = t2.user_id
where 1=1
and t2.user_id = 'c7bc68c9-d6f0-da3b-e3a7-7db62fd0d567'

select * from t_balance_log t1
inner join t_order t2 on t1.source_id = t2.id
where 1=1
and t1.user_id = 'c7bc68c9-d6f0-da3b-e3a7-7db62fd0d567';



delete from t_order;
update t_user_goods set is_deleted = 'N';

delete from rx_rebate_prd.t_cache_item

