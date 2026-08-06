# Configuration and administration

Core creates `config/neroeconomy.properties`. Gameplay values are server-authoritative.

| Key | Default | Purpose |
| --- | ---: | --- |
| `startingBalance` | 100 | One-time new player mint |
| `transactionRetentionDays` | 90 | Finite audit age; zero keeps no rows |
| `maxTransactionRecords` | 10000 | Server-wide hard audit bound |
| `historyLimitPerPlayer` | 100 | Private history page bound |
| `adminMutationsEnabled` | true | Enables permission-gated admin mutations |
| `minPriceMultiplier` / `maxPriceMultiplier` | 0.25 / 4.0 | Shock/modifier caps |
| `executionPriceTolerance` | 0.05 | Maximum quote movement accepted |
| `defaultListingDurationHours` | 48 | Offer lifetime |
| `auctionAntiSnipeSeconds` | 60 | Late-bid extension window |
| `creditChipValue` | 100 | Atomic redemption value |
| `playerBountiesEnabled` | false | Enables player-target bounties |
| `remoteTradeEnabled` | true | Enables validated Core Link trade |
| `telemetryEnabled` | true | Local opt-out; telemetry is no-PII and placeholder-inert |

Changing starting balance does not re-mint existing accounts. Back up disposable test worlds before
changing economic policy.
