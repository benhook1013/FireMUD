import { useEffect, useState } from 'react';
import Button from '@mui/material/Button';
import GameEditor from './GameEditor';
import EmailLinkLanding from './EmailLinkLanding';
import { emailLinkLanding } from './emailLinkLanding';
import ScriptEditor from './ScriptEditor';
import AccountCheckoutRoute, {
  type AccountCheckoutRouteProps,
} from './account/AccountCheckoutRoute';
import reactLogo from './assets/react.svg';
import viteLogo from '/vite.svg';
import './App.css';

export interface AppProps {
  accountCheckoutRouteProps?: Omit<AccountCheckoutRouteProps, 'pathname'>;
}

function App({ accountCheckoutRouteProps }: AppProps = {}) {
  const [pathname, setPathname] = useState(() => window.location.pathname);
  const [count, setCount] = useState(0);
  const [mode, setMode] = useState<'demo' | 'game' | 'script'>('demo');

  useEffect(() => {
    const updatePathname = () => setPathname(window.location.pathname);
    window.addEventListener('popstate', updatePathname);
    return () => window.removeEventListener('popstate', updatePathname);
  }, []);

  if (emailLinkLanding) {
    return <EmailLinkLanding route={emailLinkLanding} />;
  }

  if (pathname.startsWith('/account/')) {
    return (
      <AccountCheckoutRoute
        pathname={pathname}
        {...(accountCheckoutRouteProps ?? {})}
      />
    );
  }

  return (
    <>
      <header>
        <a href="https://vite.dev" target="_blank">
          <img src={viteLogo} className="logo" alt="Vite logo" />
        </a>
        <a href="https://react.dev" target="_blank">
          <img src={reactLogo} className="logo react" alt="React logo" />
        </a>
      </header>
      <main>
        <h1>Vite + React</h1>
        <Button
          variant="outlined"
          onClick={() => setMode('game')}
          sx={{ mr: 1 }}
        >
          Game Editor
        </Button>
        <Button
          variant="outlined"
          onClick={() => setMode('script')}
          sx={{ mr: 1 }}
        >
          Script Editor
        </Button>
        <Button variant="outlined" onClick={() => setMode('demo')}>
          Demo
        </Button>
        <div className="card">
          {mode === 'game' && <GameEditor />}
          {mode === 'script' && <ScriptEditor />}
          {mode === 'demo' && (
            <>
              <Button
                variant="contained"
                onClick={() => setCount((c) => c + 1)}
              >
                count is {count}
              </Button>
              <p>
                Edit <code>src/App.tsx</code> and save to test HMR
              </p>
            </>
          )}
        </div>
        <p className="read-the-docs">
          Click on the Vite and React logos to learn more
        </p>
      </main>
    </>
  );
}

export default App;
