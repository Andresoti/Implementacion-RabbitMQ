#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -c 'CREATE DATABASE pedidos360_orders'
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -c 'CREATE DATABASE pedidos360_worker'
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname pedidos360_orders -f /schemas/orders.sql
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname pedidos360_worker -f /schemas/worker.sql
