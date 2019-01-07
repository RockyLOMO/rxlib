create table t_user
(
	id char(36) not null,
	nickname varchar(100) null,
	open_id varchar(100) null,
	alipay_name varchar(100) null,
	alipay_account varchar(100) null,
	balance bigint default 0 not null,
	freeze_amount bigint default 0 not null,
	version bigint default 0 not null,
	create_time datetime not null,
	modify_time datetime not null,
	is_deleted char not null,
	constraint t_user_id_uindex
		unique (id),
	constraint t_user_version_uindex
		unique (version)
);
alter table t_user
	add primary key (id);

	
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


create table t_balance_log
(
	id char(36) not null,
	user_id char(36) not null,
	type int not null,
	source int not null,
	source_id varchar(200) null,
	client_ip varchar(50) not null,
	pre_balance bigint not null,
	post_balance bigint not null,
	value bigint not null,
	remark varchar(500) null,
	version bigint not null,
	create_time datetime not null,
	modify_time datetime not null,
	is_deleted char not null,
	constraint t_balance_log_version_uindex
		unique (version),
	constraint t_withdraw_log_id_uindex
		unique (id)
);
alter table t_balance_log
	add primary key (id);


create table t_withdraw_log
(
	id char(36) not null,
	user_id char(36) not null,
	balance_log_id char(36) not null,
	amount bigint not null,
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

	
create table t_check_in_log
(
	id char(36) not null,
	user_id char(36) not null,
	client_ip varchar(50) not null,
	bonus bigint default 0 not null,
	create_time datetime not null,
	modify_time datetime not null,
	is_deleted char not null,
	constraint t_check_in_log_id_uindex
		unique (id)
);
alter table t_check_in_log
	add primary key (id);

	
create table t_feedback
(
	id char(36) not null,
	user_id char(36) not null,
	content varchar(1000) not null,
	reply varchar(1000) null,
	status int not null,
	create_time datetime not null,
	modify_time datetime not null,
	is_deleted char not null,
	constraint t_feedback_id_uindex
		unique (id)
);
alter table t_feedback
	add primary key (id);

	
create table t_order
(
	id char(36) not null,
	user_id char(36) not null,
	media_type int not null,
	order_no varchar(200) not null,
	goods_id varchar(200) null,
	goods_name varchar(100) not null,
	unit_price bigint not null,
	quantity int not null,
	seller_name varchar(100) not null,
	pay_amount bigint null,
	rebate_amount bigint not null,
	status int not null,
	create_time datetime not null,
	modify_time datetime not null,
	is_deleted char not null,
	constraint t_order_id_uindex
		unique (id)
);
alter table t_order
	add primary key (id);

