select o.id, o.status, c.name, count(*)
from shop.orders o
         join shop.customers c on c.id = o.customer_id
where o.status = 'new'
group by o.id, o.status, c.name