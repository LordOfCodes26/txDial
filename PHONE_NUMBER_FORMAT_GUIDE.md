# Phone Number Format Guide

This document describes the phone number format system that uses JSON configuration files to define formats dynamically. Formats are stored in the database and matched against phone numbers to apply the correct formatting.

## Supported Formats

### Format 1: 01-4XX-XXXX

**Pattern**: `01-4XX-XXXX` (for prefixes 01~12)

- **Prefix**: 01~12 (2 digits) - City code
- **District Code**: 4XX (3 digits starting with 4) - Area/District code
- **Number**: XXXX (4 digits) - Phone number

**Example**:
- Input: `0141234567`
- Formatted: `01-412-3456`
- District lookup: `prefix="01"`, `districtCode="412"`

**Database Structure**:
- City: Stored in `phone_prefix_locations.json` with `prefix="01"`
- District/Area: Stored in `phone_districts.json` with `prefix="01"` and `districtCode="412"`

### Format 2: 01-5XX-XXXX

**Pattern**: `01-5XX-XXXX` (for prefixes 01~12)

- **Prefix**: 01~12 (2 digits) - City code
- **District Code**: 5XX (3 digits starting with 5) - Area/District code
- **Number**: XXXX (4 digits) - Phone number

**Example**:
- Input: `0151234567`
- Formatted: `01-512-3456`
- District lookup: `prefix="01"`, `districtCode="512"`

**Database Structure**:
- City: Stored in `phone_prefix_locations.json` with `prefix="01"`
- District/Area: Stored in `phone_districts.json` with `prefix="01"` and `districtCode="512"`

### Format 3: 01-7XX-XXXX

**Pattern**: `01-7XX-XXXX` (for prefixes 01~12)

- **Prefix**: 01~12 (2 digits) - City code
- **District Code**: 7XX (3 digits starting with 7) - District code
- **Number**: XXXX (4 digits) - Phone number

**Example**:
- Input: `0171234567`
- Formatted: `01-712-3456`
- District lookup: `prefix="01"`, `districtCode="712"`

**Database Structure**:
- City: Stored in `phone_prefix_locations.json` with `prefix="01"`
- District: Stored in `phone_districts.json` with `prefix="01"` and `districtCode="712"`

### Format 4: 1309-X-XXXX

**Pattern**: `1309-X-XXXX` (for prefix 13)

- **Prefix**: 13 (2 digits) - City code
- **District Code**: 09 (2 digits) - District code
- **Single Digit**: X (1 digit)
- **Number**: XXXX (4 digits) - Phone number

**Example**:
- Input: `1309123456`
- Formatted: `1309-1-2345`
- District lookup: `prefix="13"`, `districtCode="09"`

**Database Structure**:
- City: Stored in `phone_prefix_locations.json` with `prefix="13"`
- District: Stored in `phone_districts.json` with `prefix="13"` and `districtCode="09"`

## How It Works

1. **Format Loading**: Phone number formats are loaded from `phone_number_formats.json` into the database on app startup
2. **Phone Number Detection**: When formatting a number:
   - Try formats sorted by prefix length (longer prefixes first, e.g., 0219 before 021)
   - For each format, extract prefix based on `prefixLength` (2, 3, or 4 digits)
   - Check if prefix matches the format's prefix (or "all" for global formats)
   - Extract district code based on `districtCodeLength`
   - Try to match district code patterns (4XX, 5XX, 7XX, 09, XX, X, etc.)
   - Use the first matching format
3. **Formatting**: If a format matches:
   - Extract prefix and district code based on their lengths
   - Apply `formatTemplate` using placeholders
   - Return formatted number
4. **Location Lookup**: 
   - **Uses the same format matching logic as formatting** to accurately extract prefix and district code
   - First tries to match the phone number to a format definition
   - If a format matches, extracts the exact prefix and district code from the matched format
   - City is looked up using the extracted prefix (supports variable lengths: 2, 3, or 4 digits)
   - District is looked up using the extracted prefix + district code
   - Falls back to trial-and-error method if no format matches
5. **Display**: 
   - Phone number is displayed in formatted format (e.g., `01-712-3456`, `021-12-3456`, `0219-1-2345`)
   - Location is displayed as "City" or "City - District" below the phone number

## Database Files

### phone_prefix_locations.json
Contains city/location mappings:
```json
[
  {"prefix": "01", "location": ""},
  {"prefix": "02", "location": "Busan"},
  ...
  {"prefix": "13", "location": "City Name"}
]
```

### phone_districts.json
Contains district/area mappings:
```json
[
  {"prefix": "01", "districtCode": "412", "districtName": "Area 1"},
  {"prefix": "01", "districtCode": "413", "districtName": "Area 2"},
  {"prefix": "01", "districtCode": "512", "districtName": "Area 3"},
  {"prefix": "01", "districtCode": "513", "districtName": "Area 4"},
  {"prefix": "01", "districtCode": "712", "districtName": "Gangnam"},
  {"prefix": "01", "districtCode": "713", "districtName": "Gangdong"},
  ...
  {"prefix": "13", "districtCode": "09", "districtName": "District Name"}
]
```

### phone_number_formats.json (NEW)
Contains phone number format definitions with support for variable prefix lengths (2, 3, or 4 digits):
```json
[
  {
    "prefix": "01",
    "prefixLength": 2,
    "districtCodePattern": "4XX",
    "formatTemplate": "{PREFIX}-{DISTRICT}-{NUMBER4}",
    "districtCodeLength": 3,
    "description": "Area format starting with 4: 01-4XX-XXXX"
  },
  {
    "prefix": "01",
    "prefixLength": 2,
    "districtCodePattern": "5XX",
    "formatTemplate": "{PREFIX}-{DISTRICT}-{NUMBER4}",
    "districtCodeLength": 3,
    "description": "Area format starting with 5: 01-5XX-XXXX"
  },
  {
    "prefix": "01",
    "prefixLength": 2,
    "districtCodePattern": "7XX",
    "formatTemplate": "{PREFIX}-{DISTRICT}-{NUMBER4}",
    "districtCodeLength": 3,
    "description": "District format starting with 7: 01-7XX-XXXX"
  },
  {
    "prefix": "13",
    "prefixLength": 2,
    "districtCodePattern": "09",
    "formatTemplate": "1309-{NUMBER1}-{NUMBER4}",
    "districtCodeLength": 2,
    "description": "Special format for prefix 13: 1309-X-XXXX"
  },
  {
    "prefix": "021",
    "prefixLength": 3,
    "districtCodePattern": "XX",
    "formatTemplate": "{PREFIX}-{DISTRICT}-{NUMBER4}",
    "districtCodeLength": 2,
    "description": "Format for 3-digit prefix: 021-XX-XXXX"
  },
  {
    "prefix": "0219",
    "prefixLength": 4,
    "districtCodePattern": "X",
    "formatTemplate": "{PREFIX}-{DISTRICT}-{NUMBER4}",
    "districtCodeLength": 1,
    "description": "Format for 4-digit prefix: 0219-X-XXXX"
  }
]
```

**Format Template Placeholders:**
- `{PREFIX}`: Replaced with prefix (e.g., "01", "13")
- `{DISTRICT}`: Replaced with district code (e.g., "412", "09")
- `{NUMBER}`: Replaced with all remaining number digits
- `{NUMBER4}`: Replaced with last 4 digits of number
- `{NUMBER1}`: Replaced with first digit of number

**Pattern Matching:**
- `prefix`: City prefix (can be 2, 3, or 4 digits like "01", "021", "0219") or "all" for global formats
- `prefixLength`: Length of prefix (2, 3, or 4 digits)
- `districtCodePattern`: Pattern like "4XX", "5XX", "7XX", "09", "XX", "X"
  - `X` matches any digit
  - Example: "4XX" matches "412", "413", "414", etc.
  - Example: "XX" matches "12", "34", "56", etc.
  - Example: "X" matches "1", "2", "3", etc.
- `districtCodeLength`: Length of district code (1, 2, or 3 digits)
- `formatTemplate`: Template with placeholders (see below)

**Examples:**
- `021-**-****`: prefix="021", prefixLength=3, districtCodePattern="XX", districtCodeLength=2
- `0219-*-****`: prefix="0219", prefixLength=4, districtCodePattern="X", districtCodeLength=1

## Implementation Details

- **Formatting Function**: `String.formatPhoneNumberWithDistrict()`
- **Location Detection**: `String.getLocationByPrefixAsync()`
- **Database**: Room database with `PhonePrefixLocation` and `PhoneDistrict` entities
- **Auto-loading**: Data is automatically loaded from JSON files on app startup

## Adding New Formats

To add a new phone number format, simply add an entry to `phone_number_formats.json`:

**Example 1: 2-digit prefix**
```json
{
  "prefix": "02",
  "prefixLength": 2,
  "districtCodePattern": "3XX",
  "formatTemplate": "{PREFIX}-{DISTRICT}-{NUMBER4}",
  "districtCodeLength": 3,
  "description": "Format for prefix 02: 02-3XX-XXXX"
}
```

**Example 2: 3-digit prefix (021-**-****)**
```json
{
  "prefix": "021",
  "prefixLength": 3,
  "districtCodePattern": "XX",
  "formatTemplate": "{PREFIX}-{DISTRICT}-{NUMBER4}",
  "districtCodeLength": 2,
  "description": "Format for 3-digit prefix: 021-XX-XXXX"
}
```

**Example 3: 4-digit prefix (0219-*-****)**
```json
{
  "prefix": "0219",
  "prefixLength": 4,
  "districtCodePattern": "X",
  "formatTemplate": "{PREFIX}-{DISTRICT}-{NUMBER4}",
  "districtCodeLength": 1,
  "description": "Format for 4-digit prefix: 0219-X-XXXX"
}
```

**Key Points:**
- `prefix`: Can be 2, 3, or 4 digits (e.g., "01", "021", "0219") or "all" for global formats
- `prefixLength`: Must match the length of the prefix (2, 3, or 4)
- `districtCodePattern`: Use X for wildcard digits (e.g., "4XX", "XX", "X", "09")
- `formatTemplate`: Use placeholders like {PREFIX}, {DISTRICT}, {NUMBER4}
- `districtCodeLength`: Must match the actual length of district codes (1, 2, or 3)
- Formats are matched by trying longer prefixes first (0219 before 021 before 02)

## Notes

- Formatting only applies when `config.formatPhoneNumbers` is enabled
- Formats are loaded from JSON and stored in database for fast lookup
- The system supports variable prefix lengths (2, 3, or 4 digits)
- Formats are tried in order of prefix length (longer prefixes first) to avoid conflicts
- The system tries to match formats by checking prefix and district code patterns
- If no format matches, falls back to default phone number formatting
- If no district match is found, only the city name is displayed
- The system supports unlimited formats - just add them to the JSON file
- Formats are matched dynamically based on prefix length, prefix value, and district code pattern
- Make sure `prefixLength` matches the actual length of your prefix string

