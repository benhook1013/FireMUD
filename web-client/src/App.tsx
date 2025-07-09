import { useState } from 'react';
import Button from '@mui/material/Button';
import GameEditor from './GameEditor';
import reactLogo from './assets/react.svg';
import viteLogo from '/vite.svg';
import './App.css';

function App() {
  const [count, setCount] = useState(0);
  const [showEditor, setShowEditor] = useState(false);

  return (
    <>
      <div>
        <a href="https://vite.dev" target="_blank">
          <img src={viteLogo} className="logo" alt="Vite logo" />
        </a>
        <a href="https://react.dev" target="_blank">
          <img src={reactLogo} className="logo react" alt="React logo" />
        </a>
      </div>
      <h1>Vite + React</h1>
      <Button variant="outlined" onClick={() => setShowEditor(!showEditor)}>
        {showEditor ? 'Back to Demo' : 'Open Game Editor'}
      </Button>
      <div className="card">
        {!showEditor ? (
          <>
            <Button
              variant="contained"
              onClick={() => setCount((count) => count + 1)}
            >
              count is {count}
            </Button>
            <p>
              Edit <code>src/App.tsx</code> and save to test HMR
            </p>
          </>
        ) : (
          <GameEditor />
        )}
      </div>
      <p className="read-the-docs">
        Click on the Vite and React logos to learn more
      </p>
    </>
  );
}

export default App;
