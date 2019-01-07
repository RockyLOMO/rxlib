create table t_user_goods
(
	id char(36) not null,
	user_id char(36) not null,
	media_type int not null,
	goods_id varchar(100) not null,
	create_time datetime not null,
	modify_time datetime not null,
	is_deleted char not null,
	constraint t_user_goods_id_uindex
		unique (id)
);
alter table t_user_goods
	add primary key (id);


create table t_withdraw_log
(
	id char(36) not null,
	user_id char(36) not null,
	balance_log_id char(36) not null,
	status int not null,
	remark varchar(1000) null,
	create_time datetime not null,
	modify_time datetime not null,
	is_deleted char not null,
	constraint t_withdraw_log_id_uindex
		unique (id)
);
alter table t_withdraw_log
	add primary key (id);
