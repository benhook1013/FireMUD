import TextField from '@mui/material/TextField';
import Box from '@mui/material/Box';
import { useGreetingQuery } from './api/firemudApi';

export default function GameEditor() {
  const { data, isLoading } = useGreetingQuery();
  return (
    <Box sx={{ p: 2 }}>
      <h2>Game Editor</h2>
      <TextField label="Room Name" placeholder="e.g., Dark Forest" fullWidth />
      <p>{isLoading ? 'Loading...' : data?.message}</p>
    </Box>
  );
}
