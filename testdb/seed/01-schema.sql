-- Test seed schema
create schema shop;
create table shop.customers (
  id serial primary key,
  name text not null,
  email text unique,
  created_at timestamptz not null default now()
);
create table shop.products (
  id serial primary key,
  sku varchar(32) not null unique,
  title text not null,
  price numeric(10,2) not null check (price >= 0)
);
create table shop.orders (
  id serial primary key,
  customer_id int not null references shop.customers(id),
  status varchar(16) not null default 'new',
  ordered_at timestamptz not null default now()
);
create index orders_customer_idx on shop.orders(customer_id);
create table shop.order_items (
  order_id int not null references shop.orders(id) on delete cascade,
  product_id int not null references shop.products(id),
  qty int not null default 1,
  primary key (order_id, product_id)
);
create view shop.order_totals as
  select o.id as order_id, sum(i.qty * p.price) as total
  from shop.orders o join shop.order_items i on i.order_id = o.id
  join shop.products p on p.id = i.product_id group by o.id;

insert into shop.customers(name,email)
  select 'customer ' || g, 'c' || g || '@example.test' from generate_series(1,50) g;
insert into shop.products(sku,title,price)
  select 'SKU-' || g, 'product ' || g, (g * 1.25)::numeric(10,2) from generate_series(1,20) g;
insert into shop.orders(customer_id,status)
  select 1 + (g % 50), (array['new','paid','shipped'])[1 + g % 3] from generate_series(1,200) g;
insert into shop.order_items(order_id,product_id,qty)
  select o.id, 1 + (o.id % 20), 1 + (o.id % 4) from shop.orders o;
