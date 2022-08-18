#!/usr/bin/env bash
set -euv

mkdir -p target

if [ -d target/gh-pages ]; then
  echo "Removing former gh-pages clone"
  rm -rf target/gh-pages
fi

echo "Cloning"
git clone "https://${GH_TOKEN}@github.com/$REPO.git" -q -b gh-pages target/gh-pages
cd target/gh-pages

git config user.name "Github Actions"
git config user.email "actions@github.com"

echo "Cleaning-up gh-pages"
mkdir .tmp
while read i; do
  if [ -e "$i" ]; then
    git mv "$i" .tmp/
  fi
done < <(cat .keep)
git rm -r *
while read i; do
  if [ -e ".tmp/$i" ]; then
    git mv ".tmp/$i" .
  fi
done < <(cat .keep)
rmdir .tmp

echo "Copying new website"
cd -
cp -pR "$WEBSITE_DIR/build/"*/* target/gh-pages/
cd target/gh-pages
git add .

MSG="Update website"

# probably not fine with i18n
if git status | grep "nothing to commit" >/dev/null 2>&1; then
  echo "Nothing changed"
else
  git commit -m "$MSG"

  echo "Pushing changes"
  git push origin gh-pages
fi
