echo "===== Cleaning up any container using port 4449 ====="
docker ps -a --filter "publish=4449" -q | xargs -r docker rm -f

echo "===== Starting new container ====="
CONTAINER_ID=$(docker run -d -e SE_NODE_SESSION_TIMEOUT=5000 -e http_proxy='http://proxy-euie.aws.target.net:3128' -e https_proxy='http://proxy-euie.aws.target.net:3128' -e no_proxy='localhost,127.0.0.1,172.17.0.0/16' -p 4449:4443 --shm-size='6g' selenium/standalone-chrome:latest)

echo "Container started: $CONTAINER_ID"
sleep 15

echo "===== Container status ====="
docker ps -a | grep "$CONTAINER_ID" || echo "Container NOT running — check logs below"

echo "===== Grid health check ====="
curl -s http://localhost:4449/wd/hub/status || echo "STATUS CHECK FAILED — Grid not responding"
echo ""

echo "===== Container logs ====="
docker logs "$CONTAINER_ID" || echo "Could not fetch logs — container may not exist"
