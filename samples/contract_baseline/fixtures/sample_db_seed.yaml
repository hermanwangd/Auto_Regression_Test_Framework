create table if not exists ORDERS (
  ORDER_ID varchar(64) primary key,
  STATUS varchar(32)
);
merge into ORDERS (ORDER_ID, STATUS) key (ORDER_ID) values (:order_id, 'READY');
