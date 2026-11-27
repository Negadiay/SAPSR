// --- Нечёткий поиск ---
const levenshtein = (a, b) => {
  const m = a.length, n = b.length;
  const dp = Array.from({ length: m + 1 }, (_, i) =>
    Array.from({ length: n + 1 }, (_, j) => (j === 0 ? i : 0))
  );
  for (let j = 1; j <= n; j++) dp[0][j] = j;
  for (let i = 1; i <= m; i++)
    for (let j = 1; j <= n; j++)
      dp[i][j] = a[i - 1] === b[j - 1]
        ? dp[i - 1][j - 1]
        : 1 + Math.min(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]);
  return dp[m][n];
};

const fuzzyToken = (token, haystack) => {
  if (haystack.includes(token)) return true;
  const words = haystack.split(/[\s,._-]+/).filter(Boolean);
  const threshold = token.length <= 3 ? 0 : token.length <= 5 ? 1 : 2;
  return words.some(word => {
    if (word.startsWith(token)) return true;
    if (word.length >= token.length - 1 && word.length <= token.length + 2)
      return levenshtein(token, word) <= threshold;
    return false;
  });
};

// В именах допускаем опечатки, а цифровые токены (группа) ищем строго.
export const matchesSearch = (query, values) => {
  const normalized = query.trim().toLowerCase();
  if (!normalized) return true;
  const tokens = normalized.split(/\s+/).filter(Boolean);
  const haystack = values.filter(Boolean).map(v => String(v).toLowerCase()).join(' ');
  return tokens.every(token => (
    /^\d+$/.test(token) ? haystack.includes(token) : fuzzyToken(token, haystack)
  ));
};
