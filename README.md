# vecDB

Учебная векторная база данных на Java.

## Статус

| Этап | Описание | Статус |
|------|----------|--------|
| 1 | Интерфейсы и скелет | ✅ |
| 2 | In-memory реализация | ⬜ |
| 3 | REST-сервер | ⬜ |
| 4 | Репликация | ⬜ |
| 5 | Шардирование | ⬜ |

## Сборка

```bash
mvn clean compile
mvn test
```


## Теоретические основы

Векторная база данных хранит эмбеддинги, числовые представления объектов (текст, изображения и т.д.), и позволяет искать ближайшие по смыслу записи.

| Категория | Операции в проекте | Где задано |
|-----------|-------------------|------------|
| **Базовые** | CRUD по id, размер, очистка, закрытие, статистика | `IRamDB`, `IDatabase` |
| **Отличительные** | k-NN поиск, метрики расстояния, фиксированная размерность | `INearestSearch`, `IIndex`, `DistanceMetric`, `DistanceFunctions` |
| **Расширения** | Персистентность, REST-команды, репликация, шарды | `IPersistable`, `ICommandDispatcher`, конфиг |


## Архитектура

Скелет построен по принципу разделения ответственности: хранилище, индекс, персистентность и транспорт.

```
                    ┌─────────────────────────────────────┐
                    │           ICommandDispatcher         │  ← этап 3 (REST)
                    │     dispatch(List<String> command)   │
                    └──────────────────┬──────────────────┘
                                       │ вызывает
                    ┌──────────────────▼──────────────────┐
                    │              IVectorDB               │  ← фасад (этап 2)
                    │  IDatabase + IRamDB + INearestSearch │
                    │            + IPersistable            │
                    └──────┬───────────────┬────────────────┘
                           │               │
              ┌────────────▼────┐   ┌──────▼──────────┐
              │  Map<id, Entry> │   │     IIndex       │  ← k-NN (этап 2)
              │   (хранилище)   │   │  getNearest(k)  │
              └─────────────────┘   └─────────────────┘
                           │
              ┌────────────▼────┐
              │   DbConfig      │  dimension, metric, paths
              │ DistanceFunctions│
              └─────────────────┘
```

### Фасад — `IVectorDB`

```java
public interface IVectorDB
        extends IDatabase, IRamDB, INearestSearch, IPersistable { }
```

Единая точка входа для клиентского кода и тестов.

### Разбиение интерфейсов

| Интерфейс | Ответственность | Зачем отдельно |
|-----------|-----------------|----------------|
| `IDatabase` | Имя, `close()`, `stats()`, `isClosed()` | Жизненный цикл и наблюдаемость; пригодится для репликации (этап 4) |
| `IRamDB` | `put`, `getById`, `delete`, `exists`, `size`, `clear` | Классический key-value слой поверх векторов |
| `INearestSearch` | `getNearest(query, k)` | Главная векторная операция, отделена от CRUD |
| `IPersistable` | `save`, `load`, `flush` | Отделяет работу с данными в RAM от записи/чтения с носителя. Персистентность можно подменить (файл, WAL) без смены API. |
| `IIndex` | Индекс для k-NN: `add`, `remove`, `getNearest` | Brute-force или HNSW/LSH на этап 2 |
| `ICommandDispatcher` | `dispatch(List<String>)` | Текстовый протокол для REST (этап 3): `PUT id 1 2 3`, `SEARCH 1 2 3 5` |

Для `IPersistable`:
save() - записать текущее состояние БД на диск
load() - восстановить состояние из файлов в dataDirectory
flush() - синхронный сброс (save() без checked exception)


## Модель данных

### `VectorEntry`

```java
record VectorEntry(String id, double[] vector, byte[] data)
```

- **id** — уникальный ключ записи (как primary key).
- **vector** — эмбеддинг фиксированной размерности (задаётся в `DbConfig.dimension`).
- **data** — произвольная полезная нагрузка (метаданные, текст, JSON). Типичный паттерн vector DB: вектор для поиска, payload для отображения результата.

Валидация id и непустого вектора выполняется в конструкторе record, также проверка размерности.

### `SearchResult`

```java
record SearchResult(VectorEntry entry, double distance)
```

Результат k-NN: найденная запись и расстояние до запроса. Меньшее значение - ближе (для всех метрик).

### `DbStats`

Метрики для мониторинга и отладки: число векторов, счётчики операций, hit/miss ratio, время последнего сохранения. Поля `evictedVectors` и `maxVectors` для вытеснения при переполнении (возможно реализуем на этапе 2).


## Метрики расстояния

`DistanceMetric` — три стандартных метрики для эмбеддингов:

| Метрика | Смысл | Когда использовать |
|---------|-------|-------------------|
| `COSINE` | Угол между векторами (1 − cos θ) | Нормализованные эмбеддинги NLP |
| `EUCLIDEAN` | L2-расстояние в пространстве | Классическая геометрия |
| `DOT_PRODUCT` | −(a·b), инвертирован для единообразия «меньше = ближе» | Максимизация скалярного произведения |

Реализация в `DistanceFunctions.compute(a, b, metric)` — обёртка над использованием конкретной метрики.


## Конфигурация — `DbConfig`

| Параметр | По умолчанию | Назначение |
|----------|--------------|------------|
| `dataDirectory` | `./data` | Каталог для save/load |
| `dimension` | 128 | Ожидаемая размерность векторов |
| `metric` | `COSINE` | Метрика для k-NN |
| `maxVectors` | 0 (без лимита) | Верхняя граница записей |
| `saveInterval` | 5 сек | Период автосохранения (этап 2) |

Builder-паттерн позволяет собирать конфиг из файла, аргументов CLI или REST без изменения интерфейсов.


## Исключения

Иерархия `DbException`:

- `StorageException` — ошибки save/load/flush
- `DimensionMismatchException` — вектор недопустимой размерности
- `IdNotFoundException`, `InvalidIdException` — ошибки идентификатора


## Тестирование

`VectorStoreContractTest` — абстрактный набор тестов для любой реализации `IVectorDB`:

- CRUD: put, get, delete, overwrite, clear
- k-NN: ближайший сосед, ограничение k, пустая база
- валидация: пустой id

На этапе 2 достаточно создать класс вида `class InMemoryVectorDBTest extends VectorStoreContractTest` с методом `createStore()` — и весь контракт проверится автоматически.


## План развития по этапам

### Этап 2 — реализация

```
InMemoryVectorDB implements IVectorDB
├── HashMap<String, VectorEntry> storage
├── BruteForceIndex implements IIndex
├── синхронизация storage и index при put/delete
├── проверки из DbConfig
└── save/load в dataDirectory (простой бинарный или JSON формат)
```

### Этап 3 — REST

```
HttpServer / Spring Boot
└── RestCommandDispatcher implements ICommandDispatcher
    └── парсинг команд, вызовы IVectorDB
```

Пример протокола:

```
PUT  vec-1  0.1 0.2 0.3
GET  vec-1
DELETE vec-1
SEARCH 0.1 0.2 0.3  5
STATS
SAVE
```

### Этап 4 — репликация

- Primary принимает записи, replica синхронизируется. Переключаемся на replica при недоступности primary.
- `IPersistable.save/load` + журнал операций WAL для синхронизации (пока не придумали, как проще)
- `IDatabase.stats()` для мониторинга lag

### Этап 5 — шардирование

- Шард - отдельный экземпляр `IVectorDB` со своим `dataDirectory`
- Маршрутизация по hash(id) или по диапазону id или придумаем более умный кейс.
- `ICommandDispatcher` на верхнем уровне направляет команду нужному шарду

