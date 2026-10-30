#!/bin/bash

# If DEBUG env var is set to "true" then set -x to enable debug mode
if [ "$DEBUG" == "true" ]; then
	set -x
	EDIFICE_CLI_DEBUG_OPTION="--debug"
else
	EDIFICE_CLI_DEBUG_OPTION=""
fi

init() {
  me=`id -u`:`id -g`
  echo "DEFAULT_DOCKER_USER=$me" > .env

  # If CLI_VERSION is empty set to latest
  if [ -z "$CLI_VERSION" ]; then
    CLI_VERSION="latest"
  fi
  # Create a build.compose.yaml file from following template
  cat <<EOF > build.compose.yaml
services:
  edifice-cli:
    image: opendigitaleducation/edifice-cli:$CLI_VERSION
    user: "$DEFAULT_DOCKER_USER"
EOF
  # Copy /root/edifice from edifice-cli container to host machine
  docker compose -f build.compose.yaml create edifice-cli
  docker compose -f build.compose.yaml cp edifice-cli:/root/edifice ./edifice
  docker compose -f build.compose.yaml rm -fsv edifice-cli
  rm -f build.compose.yaml
  chmod +x edifice
  ./edifice version $EDIFICE_CLI_DEBUG_OPTION
}

# Clean files
echo -e '\n------------------'
echo 'Clean before build'
echo '------------------'
cd backend
rm -rf ./.gradle
rm -rf ./build
rm -rf ./gradle
# public/ est vidé SAUF les modèles de documents vierges, versionnés dans les sources (sinon « Créer un document » échoue en 500)
find ./src/main/resources/public -mindepth 1 -maxdepth 1 ! -name nextcloud-templates -exec rm -rf {} + 2>/dev/null || true
rm -rf ./src/main/resources/view
echo 'Repo clean for build !'
cd ..

# Frontend
echo -e '\n--------------'
echo 'Build Frontend'
echo '--------------'
cd frontend
#./build.sh --no-docker clean init build
chmod +x build.sh
./build.sh installDeps build
cd ..

# AngularJS
echo -e '\n---------------'
echo 'Build AngularJS'
echo '---------------'
cd angularjs
chmod +x build.sh
./build.sh buildNode
cd ..

# Create directory structure and copy frontend dist
echo -e '\n--------------------'
echo 'Copy front files built'
echo '----------------------'
cd backend
cp -R ../frontend/dist/* ./src/main/resources

# Move old ui to src/main/resources
#cp -R ../frontend/old/* ./src/main/resources/public/
#cp -R ../frontend/old/*.html ./src/main/resources/

# Create view directory and copy HTML files into Backend
mkdir -p ./src/main/resources/view
mkdir -p ./src/main/resources/public/template
mkdir -p ./src/main/resources/public/img
mkdir -p ./src/main/resources/public/js
mv ./src/main/resources/*.html ./src/main/resources/view

# Copy all built files from AngularJS into Backend
cp -R ../angularjs/src/view/* ./src/main/resources/view
# cp -R ../angularjs/src/css/* ./src/main/resources/public
cp -R ../angularjs/src/dist/* ./src/main/resources/public/js
cp -R ../angularjs/src/template/* ./src/main/resources/public/template
# cp -R ../angularjs/src/img/* ./src/main/resources/public/img

# Copy all public files from frontend into Backend
# cp -R ../frontend/public/* ./src/main/resources/public
echo 'Files all copied !'

# Build .
echo -e '\n-------------'
echo 'Build Backend'
echo '-------------'
#./build.sh --no-docker clean build
chmod +x build.sh
./build.sh clean build

# Clean up - remove compiled files in front folders
echo -e '\n-------------'
echo 'Clean front folders'
echo '-------------'
rm -rf ../frontend/dist
rm -rf ../angularjs/src/js
rm -rf ../angularjs/src/view
rm -rf ../angularjs/src/css
rm -rf ../angularjs/src/dist
echo 'Folders cleaned !'