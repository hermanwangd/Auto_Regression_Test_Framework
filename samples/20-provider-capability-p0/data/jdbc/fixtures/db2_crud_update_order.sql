update ORDERS set STATUS = 'UPDATED' where ORDER_ID = cast(:order_id as varchar(64))
