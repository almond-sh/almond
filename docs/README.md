# almond docs

## getting started

Make sure that you clone this repository with --recursive option.

If not, run `git submodule update --init --recursive`

Then, run docker-compose -f docker-compose.docs.yaml in the project root directory to generate processed pages.

### run dev server

#### run in docker

```
docker-compose up -d
```

#### run locally

```bash
cd website

npm install
// or yarn install

npm run start
// or yarn start
```


Then, access http://localhost:3000/almond 


