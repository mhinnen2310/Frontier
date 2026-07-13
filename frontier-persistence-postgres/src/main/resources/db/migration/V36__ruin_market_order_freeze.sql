CREATE TABLE settlement_ruin_market_orders (
  city_id UUID NOT NULL REFERENCES cities(id),
  order_id UUID NOT NULL REFERENCES market_orders(id),
  previous_status VARCHAR(24) NOT NULL,
  frozen_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(city_id, order_id)
);

CREATE INDEX idx_ruin_market_orders_city
  ON settlement_ruin_market_orders(city_id);
