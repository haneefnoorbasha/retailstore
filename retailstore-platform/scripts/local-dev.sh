#!/bin/bash
# Start the full RetailStore stack locally using Docker Compose
# Run from: retailstore-platform/

set -e
cd "$(dirname "$0")/.."

echo "================================================"
echo "  RetailStore — Local Dev Stack"
echo "================================================"

case "${1:-up}" in
  up)
    echo "Starting all services..."
    docker compose up --build -d
    echo ""
    echo "Services starting... Waiting for health checks..."
    sleep 15
    docker compose ps
    echo ""
    echo "API Gateway:        http://localhost:8080"
    echo "Catalog Service:    http://localhost:8081/swagger-ui.html"
    echo "Cart Service:       http://localhost:8082/swagger-ui.html"
    echo "Checkout Service:   http://localhost:8083/swagger-ui.html"
    echo "Order Service:      http://localhost:8084/swagger-ui.html"
    echo "Identity Service:   http://localhost:8085/swagger-ui.html"
    echo "Experience Service: http://localhost:8086/swagger-ui.html"
    echo ""
    echo "Start the React frontend:"
    echo "  cd web-storefront && npm install && npm run dev"
    echo "  → http://localhost:3000"
    ;;
  down)
    docker compose down
    ;;
  logs)
    docker compose logs -f "${2:-}"
    ;;
  restart)
    docker compose restart "${2:-}"
    ;;
  *)
    echo "Usage: $0 [up|down|logs|restart] [service]"
    exit 1
    ;;
esac
