package main

import (
	"sort"
	"strings"
	"unicode"
	"unicode/utf8"
)

// Library sort orders shared with the phone (LibrarySort.java) and the Edge page.
var librarySorts = map[string]bool{
	"name_asc": true, "name_desc": true, "date_desc": true,
	"date_asc": true, "size_desc": true, "size_asc": true,
}

func parseLibrarySort(value string) string {
	value = strings.ToLower(strings.TrimSpace(value))
	if librarySorts[value] {
		return value
	}
	return "date_desc"
}

// naturalCompare compares names the way a file manager does: case-insensitive,
// with digit runs compared as numbers ("ep 2" before "ep 10").
func naturalCompare(a, b string) int {
	i, j := 0, 0
	for i < len(a) && j < len(b) {
		ra, _ := utf8.DecodeRuneInString(a[i:])
		rb, _ := utf8.DecodeRuneInString(b[j:])
		da, db := unicode.IsDigit(ra), unicode.IsDigit(rb)
		ea, eb := runEnd(a, i, da), runEnd(b, j, db)
		var c int
		switch {
		case da && db:
			c = compareDigits(a[i:ea], b[j:eb])
		case da != db:
			if da {
				c = -1
			} else {
				c = 1
			}
		default:
			c = strings.Compare(strings.ToLower(a[i:ea]), strings.ToLower(b[j:eb]))
		}
		if c != 0 {
			return c
		}
		i, j = ea, eb
	}
	switch {
	case len(a)-i < len(b)-j:
		return -1
	case len(a)-i > len(b)-j:
		return 1
	}
	return strings.Compare(a, b)
}

func runEnd(s string, start int, digits bool) int {
	k := start
	for k < len(s) {
		r, size := utf8.DecodeRuneInString(s[k:])
		if unicode.IsDigit(r) != digits {
			break
		}
		k += size
	}
	return k
}

func compareDigits(a, b string) int {
	a = strings.TrimLeft(a, "0")
	b = strings.TrimLeft(b, "0")
	if len(a) != len(b) {
		if len(a) < len(b) {
			return -1
		}
		return 1
	}
	return strings.Compare(a, b)
}

// sortSharedFiles returns a sorted copy; the cached listing stays newest-first.
// Every order ends on the relative path, so paging is stable.
func sortSharedFiles(files []sharedFile, mode string) []sharedFile {
	mode = parseLibrarySort(mode)
	out := make([]sharedFile, len(files))
	copy(out, files)
	byName := func(x, y sharedFile) int {
		if c := naturalCompare(x.Name, y.Name); c != 0 {
			return c
		}
		return strings.Compare(x.Path, y.Path)
	}
	sort.SliceStable(out, func(i, j int) bool {
		x, y := out[i], out[j]
		switch mode {
		case "name_asc":
			return byName(x, y) < 0
		case "name_desc":
			if c := naturalCompare(x.Name, y.Name); c != 0 {
				return c > 0
			}
			return x.Path < y.Path
		case "date_asc":
			if x.Modified != y.Modified {
				return x.Modified < y.Modified
			}
			return byName(x, y) < 0
		case "size_desc":
			if x.Size != y.Size {
				return x.Size > y.Size
			}
			return byName(x, y) < 0
		case "size_asc":
			if x.Size != y.Size {
				return x.Size < y.Size
			}
			return byName(x, y) < 0
		default: // date_desc
			if x.Modified != y.Modified {
				return x.Modified > y.Modified
			}
			return x.Path < y.Path
		}
	})
	return out
}
