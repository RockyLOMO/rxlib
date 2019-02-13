#查找用户
select * from t_user t where 1=1
and wx_open_id != ''
#and wx_open_id = 'yan_131415'
order by t.create_time desc;

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

#人工校正流水
select t1.wx_open_id,t2.* from t_user t1
inner join t_balance_log t2 on t1.id = t2.user_id
where 1=1
and t1.wx_open_id = 'yan_131415'
order by t2.create_time desc;

#流水与订单
select t1.wx_open_id,t2.*,t3.* from t_user t1
inner join t_balance_log t2 on t1.id = t2.user_id
inner join t_order t3 on t2.source_id = t2.id
where 1=1
and t1.wx_open_id = 'yan_131415'
order by t2.create_time desc;

#提现列表
select t1.wx_open_id,t.* from t_withdraw_log t
inner join t_user t1 on t.user_id = t1.id
order by t.create_time asc

# delete from t_order;
# update t_user_goods set is_deleted = 'N';
#
# delete from rx_rebate_prd.t_cache_item

