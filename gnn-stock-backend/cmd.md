1. Xem tổng quan graph:

MATCH (n) RETURN count(n) AS total_nodes
2. Xem các Company nodes:

MATCH (c:Company) RETURN c.ticker, c.name LIMIT 20
3. Xem các CORRELATED_WITH edges:

MATCH (a:Company)-[r:CORRELATED_WITH]-(b:Company) 
RETURN a.ticker, b.ticker, r.score 
ORDER BY r.score DESC 
LIMIT 20
4. Visualize graph của một ticker (ví dụ AAA):

MATCH path = (a:Company {ticker: 'AAA'})-[*1..2]-(b:Company)
RETURN path
LIMIT 50
5. Xem các ticker có nhiều correlation nhất:


MATCH (c:Company)-[r:CORRELATED_WITH]-()
RETURN c.ticker, c.name, count(r) AS connections
ORDER BY connections DESC
LIMIT 10